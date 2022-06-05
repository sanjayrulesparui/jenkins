import java.text.SimpleDateFormat
import java.util.Date
import java.time.*
import java.util.concurrent.TimeUnit
import groovy.json.JsonSlurper

//pipeline function
def call(body) {

    // evaluate the body block, and collect configuration into the object
    def pipelineParams= [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    //local vars
    def error = false
    def sendEmail = true
    def serverType = ''
    def emailBody = ''
    def muleUsername = ''
    def mulePassword = ''
    def deployEnv = false
    def folderId = ''
    def isStartedByUser
    def forceEnv = ''

    //jenkins pipeline script
    pipeline {
        //specify agent
        agent any
        //specify tools
        tools {
            maven 'Maven'
            jdk 'JDK1.8'
            git 'Default'
            nodejs 'NodeJSJ'
        }
        //specify properties
        options {
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: '10', daysToKeepStr: '60'))
        }
        //deployment steps
        stages {
            //checkout git repo, check for diffs, grab pom vars
            stage('Initialize') {
                steps {
                    git branch: "${pipelineParams.repoBranch}", credentialsId: '38fe9ead-483e-4219-85a7-378892c2e560', url: "https://github.hologic.com/${pipelineParams.githubOrg}/${pipelineParams.repoName}.git"
                    script {

                        echo "\nChecking build trigger...\n" 

                        isStartedByUser = currentBuild.rawBuild.getCause(hudson.model.Cause$UserIdCause) != null

                        echo "Build started by user? ${isStartedByUser}" 

                        echo "\nChecking repository relative path changes...\n"
                        if (!isStartedByUser && checkFolderForDiffsFunc("${workspace}/${pipelineParams.repoPath}")) {
                            echo "No changes to project, exiting build\n"
                            error = true
                            sendEmail = false
                        } else {
                            echo "Checking auto deploy flag...\n"
                            if (getMavenPropertyFunc("${workspace}/${pipelineParams.repoPath}", "autoDeploy") != 'true') {
                                echo "Project not set to autodeploy, exiting build\n"
                                error = true
                                sendEmail = false
                            } else {
                                echo "Getting server type...\n"
                                serverType = getMavenPropertyFunc("${workspace}/${pipelineParams.repoPath}", "serverType")
                                if (serverType != 'ESB' && serverType != 'API') {
                                    echo "No server type, or invalid server type specified, exiting build\n"
                                    error = true
                                    sendEmail = false
                                } else if (!isStartedByUser) {
                                    echo "Checking build type...\n"
                                    forceEnv = getMavenPropertyFunc("${workspace}/${pipelineParams.repoPath}", "forceEnv")
                                    if (forceEnv != 'DEV' && forceEnv != 'TST'){
                                        echo "Invalid environment for force env, valid options are DEV, TST\n"
                                        error = true
                                        sendEmail = false
                                    } else {
                                        if (forceEnv == ''){
                                            echo "Standard build\n"
                                        } else if (forceEnv == 'DEV' || forceEnv == 'TST'){
                                            echo "Forcing environment: ${forceEnv}\n"
                                        }
                                    }
                                }
                            }
                        }
                        
                    }
                    //get mule_svc_account
                    withCredentials([usernamePassword(credentialsId: 'svc_jenkins_mule', passwordVariable: 'password', usernameVariable: 'username')]) {
                        script {
                            muleUsername = "${username}"
                            mulePassword = "${password}"
                        }
                    }
                }
            }
            stage ('Code Analysis') {
                when {
                    expression {
                        !error
                    }
                }
                steps {
                    echo "\nCode analysis...\n"
                    script {
                        //running lint
                        echo "Running lint...\n"
                        def projRelativePath ="${workspace}/${pipelineParams.repoPath}/src/main/app"
                        def bashPath = projRelativePath.replaceAll("\\\\", "/")
                        bashPath = bashPath.replaceAll('C:', '/c')
                        def p1issue = runLintWrapper(bashPath, "${workspace}\\${pipelineParams.repoPath}\\src\\main\\app")

                        //if p1 issue -> exit build
                        if (p1issue){
                            echo "This application has a p1 issue! Exiting build.\n"
                            error = true
                        }
                    }
                }
            }
            stage ('Pre-DEV') {
                when {
                    expression {
                        !error && forceEnv == ''
                    }
                }
                steps {
                    echo "Pre-Dev steps...\n"
                    script {
                        deployEnv = input message: 'Pre-Deployment Steps', parameters: [booleanParam(defaultValue: true, description: '', name: 'Deploy to Dev?')]
                        if (deployEnv) {
                            if (scheduleBuildFunc(muleUsername, mulePassword, "DEV")) {
                                error = true
                            }
                        }
                    }
                }
            }
            stage('Deploy DEV') {
                when {
                    expression {
                        !error && (deployEnv || forceEnv == 'DEV')
                    }
                }
                steps {
                    script {
                        if (deployFunc("${workspace}/${pipelineParams.repoPath}", "DEV", "${serverType}", muleUsername, mulePassword)) {
                            error = true
                        } else {
                            folderId = properties("boxDidev${serverType}id")
                            if (uploadBoxFunc("${workspace}/${pipelineParams.repoPath}", folderId)) {
                                error = true
                            }
                        }
                    }
                }
            }
            stage ('Pre-TST') {
                when {
                    expression {
                        !error && forceEnv == ''
                    }
                }
                steps {
                    echo "Pre-Test steps...\n"
                    script {
                        deployEnv = input message: 'Pre-Deployment Steps?', parameters: [booleanParam(defaultValue: true, description: '', name: 'Deploy to Test?')]
                        if (deployEnv) {
                            if (scheduleBuildFunc(muleUsername, mulePassword, "TST")) {
                                error = true
                            }
                        }
                    }
                }
            }
            stage('Deploy TST') {
                when {
                    expression {
                        !error && (deployEnv || forceEnv == 'TST')
                    }
                }
                steps {
                    script {
                        if (deployFunc("${workspace}/${pipelineParams.repoPath}", "TST", "${serverType}", muleUsername, mulePassword)) {
                            error = true
                        } else {
                            folderId = properties("boxDitst${serverType}id")
                            if (uploadBoxFunc("${workspace}/${pipelineParams.repoPath}", folderId)) {
                                error = true
                            }
                        }
                    }
                }
            }
            stage ('Pre-UAT') {
                when {
                    expression {
                        !error && forceEnv == ''
                    }
                }
                steps {
                    echo "Pre-UAT steps...\n"
                    
                    script {
                        deployEnv = input message: 'Pre-Deployment Steps', parameters: [booleanParam(defaultValue: true, description: '', name: 'Deploy to UAT?')]
                        if (deployEnv) {
                            if (scheduleBuildFunc(muleUsername, mulePassword, "UAT")) {
                                error = true
                            }
                        }
                    }
                }
            }
            stage ('Deploy UAT') {
                when {
                    expression {
                        !error && deployEnv && forceEnv == ''
                    }
                }
                steps {
                    script {
                        if (deployFunc("${workspace}/${pipelineParams.repoPath}", "UAT", "${serverType}", muleUsername, mulePassword)) {
                            error = true
                        } else {
                            folderId = properties("boxDiuat${serverType}id")
                            if (uploadBoxFunc("${workspace}/${pipelineParams.repoPath}", folderId)) {
                                error = true
                            }
                        }
                    }
                }
            }
            stage ('Pre-PRD') {
                when {
                    expression {
                        !error && forceEnv == ''
                    }
                }
                steps {
                    echo "Pre-PRD steps...\n"

                    script {
                        deployEnv = input message: 'Pre-Deployment Steps', parameters: [booleanParam(defaultValue: true, description: '', name: 'Deploy to PRD?')]
                        if (deployEnv){
                            if (scheduleBuildFunc(muleUsername, mulePassword, "PRD")) {
                                error = true
                            } else {
                                //check merge
                                if (checkGitMergeMaster(pipelineParams.repoBranch, workspace)){
                                    error = true
                                }
                            }
                        }
                    }
                }
            }
            stage ('Deploy PRD') {
                when {
                    expression {
                        !error && deployEnv && forceEnv == ''
                    }
                }
                steps {
                    script {
                        if (deployFunc("${workspace}/${pipelineParams.repoPath}", "PRD", "${serverType}", muleUsername, mulePassword)) {
                            error = true
                        } else {
                            folderId = properties("boxDiprd${serverType}id")
                            if (uploadBoxFunc("${workspace}/${pipelineParams.repoPath}", folderId)) {
                                error = true
                            }
                        }
                    }
                }
            }
            stage ('Build Handling') {
                steps {
                    script {
                        if (error && sendEmail){
                            echo "Errors occured during build, sending email notification\n"

                            emailBody = "Hello,\n\n"
                            emailBody += "Project ${JOB_NAME}, build #${BUILD_NUMBER} errored out during build. Please navigate to Jenkins to investigate.\n\n"
                            emailBody += "Link: https://mule-jenkins.hologic.com/job/${JOB_NAME}\n\n"
                            emailBody += "Thank you,\n"
                            emailBody += "Jenkins DevOps Team"

                            emailext body: "${emailBody}", subject: 'Jenkins Build Error Notification', to: '$DEFAULT_RECIPIENTS'

                            currentBuild.result = 'FAILURE'
                        } else {
                            currentBuild.result = 'SUCCESS'
                        }
                        echo "Build finished, exiting.\n"
                    }
                }
            }
        }
    }
}

/*********************************************************************************************************************************************************************
                                                                    Helper Functions
*********************************************************************************************************************************************************************/

//check merge into master before production deployment
def checkGitMergeMaster(featureBranch, gitPath){
    try {
        echo "\nRunning test git merge into master...\n"

        def error = false

        //convert path to bash format
		def bashPath = gitPath.replaceAll("\\\\", "/")
		bashPath = bashPath.replaceAll('C:', '/c')

        //checkout master
        sh label: '', returnStdout: false, script: "{ set +x; } 2>/dev/null && cd ${bashPath} && git checkout master --quiet"

        //run rest merge
        def mergeResult = sh (returnStdout: true, script: "{ set +x; } 2>/dev/null && cd ${bashPath} && git merge ${featureBranch} --no-commit --no-ff 2>&1").trim()

        if (mergeResult.contains('Automatic merge went well') || mergeResult.contains('Already up to date')){
                
            //reset
            sh label: '', returnStdout: false, script: "{ set +x; } 2>/dev/null && cd ${bashPath} && git reset --hard --quiet"

            //checkout feature branch
            sh label: '', returnStdout: false, script: "{ set +x; } 2>/dev/null && cd ${bashPath} && git checkout ${featureBranch} --quiet" 

        } else {
            echo "Test merge into master failed, aborting build.\n"
            error = true
        }
        echo "Test merge into master was successful, continuing with production deployment.\n" 
        return error
    } catch (err) {
        echo "Test merge into master failed, aborting build.\n"
        echo "${err}\n"
        return true
    }
}

//check git folder for differences
def checkFolderForDiffsFunc(path) {
    try {
        echo "Checking project for changes..."
        // git diff will return 1 for changes (failure) which is caught in catch, or 0 meaning no changes 
        def statusCode = bat label: '', returnStatus: true, script: "git diff --quiet --exit-code HEAD~1..HEAD ${path}"
        if (statusCode == 1) {
            echo "\nChanges made to project at path ${path}, continuing with build\n"
            return false
        } else {
            echo "Changes not made to project at path ${path}, exiting build\n"
            return true
        }
    } catch (err) {
        echo "Error while checking project for changes\n"
        echo "${err}\n"
        return true
    }
}

//deploy project per maven commands
def deployFunc(path, env, serverType, ipUser, ipPass) {
    try {
        
        def output
        def errMessage = ""
        def bashPath = path.replaceAll("\\\\", "/")
		bashPath = bashPath.replaceAll('C:', '/c')

        echo "\nDeploying mavenized project to ${env} environment...\n"

        output = sh label: '', returnStdout: true, script: "{ set +x; } 2>/dev/null && cd ${bashPath} && mvn clean package deploy -P deploy-${env}-${serverType} -D username=${ipUser} -D password=${ipPass} -q"
        output = output.replaceAll("(?<=password=)(.*)(?= )", "*****")

        echo "Deployed successfully!\n"        

        return false
    } catch (err) {
        echo "Error while deploying project\n"
        echo "${err}\n"
        return true
    }
}

//get project specific maven properties
def getMavenPropertyFunc(path, property) {
    try {
        echo "Grabbing property from project POM...\n" 
        propVal = bat (label: '', returnStdout: true, script: "cd ${path} & mvn help:evaluate -Dexpression=${property} -q -DforceStdout").trim()
        propVal = propVal.split('\n')[1]

        //if prop not set, default to ''
        if (propVal == 'null object or invalid expression'){
            propVal = ''
        }

        echo "Property Name: ${property}"
        echo "Property Value: ${propVal}\n"
        
        return propVal
    } catch (err) {
        echo "Error while grabbing property ${property}\n"
        echo "${err}\n"
        return ''
    }
}

//function to schedule deployment
def scheduleBuildFunc(svcUser, svcPass, env) {
    try {

        def validInput = false
        def userAuthorized = false
        def errMessage = ""
        def scheduleChoice
        def userInput
        def numSeconds
        def validateUserOutput = ""
        def userGrp = ""
        def submitterId
        def unAuthMessage = ""
        

        while (!userAuthorized){
            echo "\nSchedule or deploy input...\n"
            scheduleChoice = input message: unAuthMessage + 'Schedule or deploy now?', submitterParameter: 'submitter', parameters: [choice(choices: ['Schedule', 'Deploy Now'], description: '', name: 'Input')]
            submitterId = scheduleChoice.submitter

            if (scheduleChoice.Input == 'Schedule'){
                while (!validInput){
                                            
                    userInput = input message: "${errMessage}Specify Date & Time:", submitterParameter: 'submitter',
                        parameters: [string(defaultValue: '', description: 'MM/DD/YYYY', name: 'Date'),
                                    string(defaultValue: '', description: 'HH:MM (Military Time)', name: 'Time')]
                    submitterId = userInput.submitter
                    if (!dateTimeValidationFunc(userInput.Date, userInput.Time)) {
                        validInput = true
                    } else {
                        validInput = false
                        errMessage = "Invalid input... \n"
                    }
                    
                }
            }

            //get deployment group
            if (env == 'UAT' || env == 'PRD' ){
                userGrp = properties("muleUatPrdDeploymentGroup")
            } else {
                userGrp = properties("muleDevTstDeploymentGroup")
            }

            echo "\nAuthorizing user ${submitterId} for deployment... Checking group ${userGrp}..."

            validateUserOutput = sh (label: '', returnStdout: true, script: "{ set +x; } 2>/dev/null && curl https://mule-prd-esb.hologic.com/security/ad/cn%3D${userGrp}%2Cou%3DMule%2Cou%3DSecurity%20Groups%20-%20All%20locations%2Cdc%3Dhologic%2Cdc%3Dcorp?username=${submitterId} -X GET -k" )

            def slurper = new JsonSlurper()
            def validateUserOutputJson = slurper.parseText(validateUserOutput)

            if (validateUserOutputJson.status_code == 'Y' && validateUserOutputJson.has_role == 'Y'){
                userAuthorized = true
                echo "User authorized."
            } else {
                echo "User unauthorized."
                unAuthMessage = "User unauthorized... "
            }

            slurper = null
            validateUserOutputJson = null
        }

        //queue build
        if (scheduleChoice.Input == 'Schedule'){
            
            //scheduling..
            echo "Scheduling deployment...\n"
            numSeconds = dateTimeDiffFunc(userInput.Date, userInput.Time)

            //sleep
            if (numSeconds != -1) {
                sleep numSeconds
            } else {
                return true
            }
        }

        return false
    } catch (err) {
        echo "Error while scheduling deployment\n"
        echo "${err}\n"
        return true
    }
}

//get difference between inputted time and current
def dateTimeDiffFunc(date, time) {
	try {
		Date dateTime = Date.parse("MM/dd/yyyy HH:mm:ss", date + " " + time + ":00")
		Date now = new Date()
		def duration = groovy.time.TimeCategory.minus(dateTime, now)
		def durationSeconds = duration.seconds + (duration.minutes * 60) + (duration.hours * 60*60) + (duration.days * 24*60*60)
		echo "Going to sleep... Will deploy in following number of seconds:"
		echo durationSeconds.toString() + "\n"
		return durationSeconds
	} catch(err) {
		echo "Error while calculating time diff\n"
		echo "${err}\n"
		return -1
	}
}

//validate user inputted date and time
def dateTimeValidationFunc(date, time) {
	try {
		Date dateTime = Date.parse("MM/dd/yyyy HH:mm:ss", date + " " + time + ":00")
		echo "DateTime: ${dateTime}\n"
		Date now = new Date()

		if (dateTime.time - now.time < 0) {
			echo "INVALID DATE TIME INPUT\n"
			echo "Error: Date time is less than current\n"
			return true
		}
		echo "Valid input, continuing...\n"
		return false
	} catch(err) {
		echo "INVALID DATE TIME INPUT\n"
		echo "Error: ${err}\n"
		return true
	}
}

//function to upload mule deployments to box
def uploadBoxFunc(path, folderId){
	
	try {

		echo "Deploying project zip to box..."
		echo "Box folder ID: ${folderId}\n"

		//get required variables
		def artifactId = bat (label: '', returnStdout: true, script: "cd ${path} & mvn org.apache.maven.plugins:maven-help-plugin:3.1.0:evaluate -Dexpression=project.artifactId -q -DforceStdout").trim()
		def version = bat (label: '', returnStdout: true, script: "cd ${path} & mvn org.apache.maven.plugins:maven-help-plugin:3.1.0:evaluate -Dexpression=project.version -q -DforceStdout").trim()
		artifactId = artifactId.split('\n')[1]
		version = version.split('\n')[1]

		def boxUploadUser = properties("boxUser")

		//check if auth token exists
		try {
			sh (label: '', returnStdout: false, script: '{ set +x; } 2>/dev/null && test -f ${JENKINS_HOME}/secrets/boxJWT.txt')
		} catch (err) {
			//if auth token file doesnt exist, call function to generate new one
			echo "Auth token doesnt exist, generating new one...\n"
			generateAuth()
		}

		//read & decode token from file
		def jwtToken = sh (label: '', returnStdout: true, script: '{ set +x; } 2>/dev/null && base64 -d ${JENKINS_HOME}/secrets/boxJWT.txt').trim()

		//make test callout to check validity
		try{
			def tstOut = sh (label: '', returnStdout: true, script: "{ set +x; } 2>/dev/null && curl https://api.box.com/2.0/folders/0 -H \"Authorization: Bearer ${jwtToken}\" -X GET")
			if (tstOut == ""){
				throw new Exception("401 unauthorized")
			}
		} catch(err){
			echo "Box token is invalid, regenerating...\n"
			generateAuth()
			jwtToken = sh (label: '', returnStdout: true, script: '{ set +x; } 2>/dev/null && base64 -d ${JENKINS_HOME}/secrets/boxJWT.txt').trim()
		}

		//rename project zip to artifactId
		bat (label: '', returnStdout: true, script: "cd ${path}/target & ren ${artifactId}-${version}.zip ${artifactId}.zip").trim()
		
		//get list of files in box directory
		echo "\nGetting list of files in box directory...\n"
		def folderItems = sh (label: '', returnStdout: true, script: "{ set +x; } 2>/dev/null && curl https://api.box.com/2.0/folders/${folderId}/items?limit=1000 -H \"Authorization: Bearer ${jwtToken}\" -H \"As-User: ${boxUploadUser}\" -X GET").trim()
		def slurper = new JsonSlurper()
		def folderItemsJson = slurper.parseText(folderItems)

		//loop through items, check if file with artifactID as name exists
		echo "\nChecking if file exists in directory...\n"
		def existingFileId = null
		for (i = 0; i < folderItemsJson.entries.size(); i++) {
			if (folderItemsJson.entries[i].name == "${artifactId}.zip") {	
				echo "File found in directory...\n"
				existingFileId = folderItemsJson.entries[i].id
				existingFileId = existingFileId.toString()
				break
			}
		}

		//unset all json vars to resolve 'java.io.NotSerializableException: groovy.json.internal.LazyMap' errors
		slurper = null
		folderItemsJson = null

		//convert path to bash format
		def bashPath = path.replaceAll("\\\\", "/")
		bashPath = bashPath.replaceAll('C:', '/c')

		//box api output var
		def uploadResponse = ""

		echo "Uploading..."

		//if project zip doesnt exist, upload v1
		if (existingFileId == null) {
			//call box upload api
			echo "Uploading new...\n"
			uploadResponse = sh (label: '', returnStdout: true, script: "{ set +x; } 2>/dev/null && cd ${bashPath}/target && curl https://upload.box.com/api/2.0/files/content -H \"Authorization: Bearer ${jwtToken}\" -H \"As-User: ${boxUploadUser}\" -X POST -F attributes=\'{\"name\":\"${artifactId}.zip\",\"parent\":{\"id\":\"${folderId}\"}}\' -F file=@${artifactId}.zip").trim()
		} else {
			//call box upload new version api
			echo "Uploading existing...\n"
			uploadResponse = sh (label: '', returnStdout: true, script: "{ set +x; } 2>/dev/null && cd ${bashPath}/target && curl https://upload.box.com/api/2.0/files/${existingFileId}/content -H \"Authorization: Bearer ${jwtToken}\" -H \"As-User: ${boxUploadUser}\" -X POST -F attributes=\'{\"name\":\"${artifactId}.zip\",\"parent\":{\"id\":\"${folderId}\"}}\' -F file=@${artifactId}.zip").trim()
		}

        echo ""

		return false
	} catch (err) {
		echo "Error while uploading project file to box\n"
        echo "${err}\n"

        return true
	}
}

//refresh auth token for box deployments
def generateAuth(){
	
	try {

		//library resources
		def boxConfig = libraryResource 'config.txt'
		def generateJwtStr = libraryResource 'generateJWT.js'

		//check config
		try {
			sh (label: '', returnStdout: false, script: '{ set +x; } 2>/dev/null && test -f ${JENKINS_HOME}/secrets/boxConfig.json')
		} catch (err){
			//if config file doesnt exist, decode resource
			echo "Decoding config...\n"
			sh (label: '', returnStdout: false, script: "{ set +x; } 2>/dev/null && echo ${boxConfig} | base64 -d  > " + '${JENKINS_HOME}/secrets/boxConfig.json')
		}

		//run nodejs to generate jwt
		def jwtToken = sh (label: '', returnStdout: true, script: '{ set +x; } 2>/dev/null && cd ${JENKINS_HOME}/secrets && node ./generateJWT.js').trim()

		//save jwt to file
		sh (label: '', returnStdout: false, script: "{ set +x; } 2>/dev/null && echo ${jwtToken} | base64 > " + '${JENKINS_HOME}/secrets/boxJWT.txt')

		return false

	} catch (err) {
		echo "Error while generating box jwt token\n"
		echo "${err}\n"
		return true
	}
}

//run lint
def runLintWrapper(bashPath, windowsPath){

	try {
        def p1issue = false
        def respArgs

        //get list of files
        def filesListStr = sh label: '', returnStdout: true, script: "{ set +x; } 2>/dev/null && ls ${bashPath}" 
        def filesList = filesListStr.split('\n')
        for (i = 0; i < filesList.size(); i++){
            if (!filesList[i].contains('common') && filesList[i].contains('.xml')){
                echo filesList[i]
                //call lint function
                respArgs = lintFunc("${windowsPath}\\" + filesList[i], filesList[i],"3")
                p1issue = respArgs[2]
            }
        }

        return p1issue
        
    } catch (err) {
		
        echo "Error while running lint.\n"
		echo "${err}\n"
		return
	}
}
