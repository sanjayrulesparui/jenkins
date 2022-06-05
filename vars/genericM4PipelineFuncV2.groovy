import java.text.SimpleDateFormat
import java.util.Date
import java.time.*
import java.util.concurrent.TimeUnit
import groovy.json.JsonSlurper

//pipeline function
// Install the Linter extension to VSCode. Shift-Alt-V to run it.
// extension settings are...
// Crumb Url: https://mule-jenkins.hologic.com
//       Url: https://mule-jenkins.hologic.com/pipeline-model-converter/validate
//  Username: <your AD username>
def call(body) {

    // evaluate the body block, and collect configuration into the object
    def pipelineParams= [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    //local vars
    def error = false
    def errorMsg = ""
    def sendEmail = true
    def serverType = ''
    def emailBody = ''
    def muleUsername = ''
    def mulePassword = ''
    def deployEnv = [DEV: false, TST: false, UAT: false, CNV: false, PTC: false, INV: false, QAS: false, PRD: false]
    def folderId = ''
    def isStartedByUser
    def forceEnv = ''
    def currentStage = ''

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
		    	        currentStage="${STAGE_NAME}"
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
                                    if (forceEnv != 'DEV' && forceEnv != 'TST' && forceEnv != 'CNV'){
                                        echo "Invalid environment for force env, valid options are DEV, TST, CNV\n"
                                        error = true
                                        sendEmail = false
                                    } else {
                                        if (forceEnv == ''){
                                            echo "Standard build\n"
                                        } else if (forceEnv == 'DEV' || forceEnv == 'TST' || forceEnv == 'CNV'){
                                            echo "Forcing environment: ${forceEnv}\n"
                                        }
                                    }
                                } else {
									println "Started by user, so skipping forceEnv."
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
		                currentStage="${STAGE_NAME}"
                        //running lint
                        echo "Running lint...\n"
                        def projRelativePath ="${workspace}/${pipelineParams.repoPath}/src/main/mule"
                        def bashPath = projRelativePath.replaceAll("\\\\", "/")
                        bashPath = bashPath.replaceAll('C:', '/c')
                        def (lintError,lintMsg) = runLintWrapper(bashPath, "${workspace}\\${pipelineParams.repoPath}\\src\\main\\mule")
                        if (lintError){
                            echo "ERROR: ${lintMsg}\n"
                            error = true
                        } 

                        // Running merge master test
                        def (mergeError,mergeMsg) = checkGitMergeMaster(pipelineParams.repoBranch, workspace)
                        if (mergeError) {
                            echo "ERROR: ${mergeMsg}\n"
                            error = true
                        }

                        if (lintError || mergeError) {
                            error = true
                            errorMsg = "Lint: ${lintMsg}   Merge: ${mergeMsg}"
                        }
                    }
                }
            }
            stage ('Environment Choices') {
                when {
                    expression {
                        !error && isStartedByUser
                    }
                }
                steps {
                    echo "Environment selection steps...\n"
                    script {
		    	        currentStage="${STAGE_NAME}"
                        deployEnv = input message: 'Select non-Prod ENVs', parameters: [booleanParam(defaultValue: false, description: null, name: 'DEV'),
                                                                                        booleanParam(defaultValue: false, description: null, name: 'TST'),
                                                                                        booleanParam(defaultValue: false, description: null, name: 'UAT'),
                                                                                        booleanParam(defaultValue: false, description: 'PRD: auth required', name: 'PRD'),
                                                                                        booleanParam(defaultValue: false, description: null, name: 'INT'),
                                                                                        booleanParam(defaultValue: false, description: null, name: 'PTC'),
                                                                                        booleanParam(defaultValue: false, description: null, name: 'CNV'),
                                                                                        booleanParam(defaultValue: false, description: null, name: 'QAS')]
                        if (! (
                               deployEnv.DEV
                            || deployEnv.TST
                            || deployEnv.UAT
                            || deployEnv.PRD
                            || deployEnv.INT
                            || deployEnv.PTC
                            || deployEnv.CNV
                            || deployEnv.QAS
                            )) 
                        {
                            error = false
                            echo "Nothing selected, therefore, nothing do build. So be it!"
                        }
                    }
                    echo "params.DEV: ${params.DEV}"
                    echo "deployEnv: ${deployEnv}"
                    echo "deployEnv.DEV: ${deployEnv.DEV}"
                }
            }
            stage('Deploy DEV') {  // The next two stages are the same.
                when {
                    expression {
                        !error && (deployEnv.DEV || (forceEnv == 'DEV' && !isStartedByUser))
                    }
                }
                steps {
                    script {
                        tmpEnv="DEV"
		    	        currentStage="${STAGE_NAME}"
                        if (deployFunc("${workspace}/${pipelineParams.repoPath}", "${tmpEnv}", "${serverType}", muleUsername, mulePassword)) {
                            error = true
                        } else {
                            folderId = properties("boxDi${tmpEnv.toLowerCase()}V4${serverType}id")
                            if (uploadBoxFunc("${workspace}/${pipelineParams.repoPath}", folderId)) {
                                error = true
                            }
                        }
                    }
                }
            }
            stage('Deploy TST') {
                when {
                    expression {
                        !error && (deployEnv.TST || (forceEnv == 'TST' && !isStartedByUser))
                    }
                }
                steps {
                    script {
                        tmpEnv="TST"
		    	        currentStage="${STAGE_NAME}"
                        if (deployFunc("${workspace}/${pipelineParams.repoPath}", "${tmpEnv}", "${serverType}", muleUsername, mulePassword)) {
                            error = true
                        } else {
                            folderId = properties("boxDi${tmpEnv.toLowerCase()}V4${serverType}id")
                            if (uploadBoxFunc("${workspace}/${pipelineParams.repoPath}", folderId)) {
                                error = true
                            }
                        }
                    }
                }
            }
            stage('Deploy UAT') { // The remaining stages (up-to PRD) are the same except for the search/replace of UAT for env.
                when {
                    expression {
                        !error && (deployEnv.UAT)
                    }
                }
                steps {
                    script {
                        tmpEnv="UAT"
		    	        currentStage="${STAGE_NAME}"
                        if (deployFunc("${workspace}/${pipelineParams.repoPath}", "${tmpEnv}", "${serverType}", muleUsername, mulePassword)) {
                            error = true
                        } else {
                            folderId = properties("boxDi${tmpEnv.toLowerCase()}V4${serverType}id")
                            if (uploadBoxFunc("${workspace}/${pipelineParams.repoPath}", folderId)) {
                                error = true
                            }
                        }
                    }
                }
            }
            stage('Deploy PTC') {
                when {
                    expression {
                        !error && (deployEnv.PTC)
                    }
                }
                steps {
                    script {
                        tmpEnv="PTC"
		    	        currentStage="${STAGE_NAME}"
                        if (deployFunc("${workspace}/${pipelineParams.repoPath}", "${tmpEnv}", "${serverType}", muleUsername, mulePassword)) {
                            error = true
                        } else {
                            folderId = properties("boxDi${tmpEnv.toLowerCase()}V4${serverType}id")
                            if (uploadBoxFunc("${workspace}/${pipelineParams.repoPath}", folderId)) {
                                error = true
                            }
                        }
                    }
                }
            }
            stage('Deploy INT') {
                when {
                    expression {
                        !error && (deployEnv.INT)
                    }
                }
                steps {
                    script {
                        tmpEnv="INT"
		    	        currentStage="${STAGE_NAME}"
                        if (deployFunc("${workspace}/${pipelineParams.repoPath}", "${tmpEnv}", "${serverType}", muleUsername, mulePassword)) {
                            error = true
                        } else {
                            folderId = properties("boxDi${tmpEnv.toLowerCase()}V4${serverType}id")
                            if (uploadBoxFunc("${workspace}/${pipelineParams.repoPath}", folderId)) {
                                error = true
                            }
                        }
                    }
                }
            }
            stage('Deploy QAS') {
                when {
                    expression {
                        !error && (deployEnv.QAS)
                    }
                }
                steps {
                    script {
                        tmpEnv="QAS"
		    	        currentStage="${STAGE_NAME}"
                        if (deployFunc("${workspace}/${pipelineParams.repoPath}", "${tmpEnv}", "${serverType}", muleUsername, mulePassword)) {
                            error = true
                        } else {
                            folderId = properties("boxDi${tmpEnv.toLowerCase()}V4${serverType}id")
                            if (uploadBoxFunc("${workspace}/${pipelineParams.repoPath}", folderId)) {
                                error = true
                            }
                        }
                    }
                }
            }
            stage('Deploy CNV') {
                when {
                    expression {
                        !error && (deployEnv.CNV || (forceEnv == 'CNV' && !isStartedByUser))
                    }
                }
                steps {
                    script {
                        tmpEnv="CNV"
		    	        currentStage="${STAGE_NAME}"
                        if (deployFunc("${workspace}/${pipelineParams.repoPath}", "${tmpEnv}", "${serverType}", muleUsername, mulePassword)) {
                            error = true
                        } else {
                            folderId = properties("boxDi${tmpEnv.toLowerCase()}V4${serverType}id")
                            if (uploadBoxFunc("${workspace}/${pipelineParams.repoPath}", folderId)) {
                                error = true
                            }
                        }
                    }
                }
            }
            stage ('PRD Auth') {
                when {
                    expression {
                        !error && deployEnv.PRD
                    }
                }
                steps {
                    echo "Pre-PRD steps...\n"
                    script {
		                currentStage="${STAGE_NAME}"
                        if (authAndScheduleBuildFunc(muleUsername, mulePassword, "PRD")) {
                            error = true
                        } else {
                            //check if master merge would be successful
                            def (mergeError,mergeMsg) = checkGitMergeMaster(pipelineParams.repoBranch, workspace)
                            if (mergeError) {
                                echo "ERROR: ${mergeMsg}"
                                error = true
                            }
                        }
                    }
                }
            }
            stage ('Deploy PRD') {
                when {
                    expression {
                        !error && deployEnv.PRD && isStartedByUser
                    }
                }
                steps {
                    script {
                        tmpEnv="PRD"
		                currentStage="${STAGE_NAME}"
                        if (deployFunc("${workspace}/${pipelineParams.repoPath}", "${tmpEnv}", "${serverType}", muleUsername, mulePassword)) {
                            error = true
                        } else {
                            folderId = properties("boxDi${tmpEnv.toLowerCase()}V4${serverType}id")
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

                            emailBody = "The Jenkins pipeline failed.\n\n"
                            emailBody += "Project: ${JOB_NAME}\n"
                            emailBody += "  Build: #${BUILD_NUMBER}\n"
                            emailBody += "  Stage: ${currentStage}\n"
                            //emailBody += "  Cause: ${BUILD_CAUSE_JSON}\n"
                            emailBody += " Status: ${errorMsg}\n"
                            emailBody += "\n\n"
                            emailBody += "Please navigate to Jenkins to investigate.\n\n"
                            emailBody += "Link: https://mule-jenkins.hologic.com/job/${JOB_NAME}/${BUILD_NUMBER}/console\n\n"
                            emailBody += "Thank you,\n"
                            emailBody += "Jenkins DevOps Team"

                            def jobList=JOB_NAME.split("\\.\\.\\.")
                            def story="unknown"
                            if (jobList.length > 2) {
                                story = jobList[2]
                            }
                            emailext body: "${emailBody}", subject: "Jenkins Build Error: ${JOB_NAME}", to: '$DEFAULT_RECIPIENTS'

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

//check merge into master. Returns an array: failBuild(boolean), statusMsg(string)
def checkGitMergeMaster(featureBranch, gitPath){

    def error = false
    def statusMsg = ""

    try {
        echo "\nRunning test git merge into master...\n"

        //convert path to bash format
		def bashPath = gitPath.replaceAll("\\\\", "/")
		bashPath = bashPath.replaceAll('C:', '/c')

        //checkout master
        sh label: '', returnStdout: false, script: "{ set +x; } 2>/dev/null && cd ${bashPath} && git checkout master --quiet"

        //run test merge
        def mergeResult = sh (returnStdout: true, script: "{ set +x; } 2>/dev/null && cd ${bashPath} && git merge ${featureBranch} --no-commit --no-ff 2>&1").trim()

        if (mergeResult.contains('Automatic merge went well') || mergeResult.contains('Already up to date')){
                
            //reset
            sh label: '', returnStdout: false, script: "{ set +x; } 2>/dev/null && cd ${bashPath} && git reset --hard --quiet"

            //checkout feature branch
            sh label: '', returnStdout: false, script: "{ set +x; } 2>/dev/null && cd ${bashPath} && git checkout ${featureBranch} --quiet" 

        } else {
            echo "Test merge into master failed.\n"
            error = true
        }
        echo "Test merge into master was successful.\n" 
        return [error,statusMsg]
    } catch (err) {
        error = true 
        statusMsg = "${err}\n"
        echo "Test merge into master failed. ${statusMsg}\n"
        return [error,statusMsg]
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

	def output

    try {
        
        
        def errMessage = ""
        def bashPath = path.replaceAll("\\\\", "/")
		bashPath = bashPath.replaceAll('C:', '/c')

		def dateStr = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss").format(new Date())

        echo "\nDeploying mavenized project to ${env} environment... ${dateStr}   ${bashPath}\n"
		
        //output = sh label: '', returnStdout: true, script: "cd ${bashPath} && mvn help:system"
		//print "mvn help:system = ${output}\n"
		//{ set +x; } 2>/dev/null && 
        output = sh label: '', returnStdout: true, script: "{ set +x; } 2>/dev/null && cd ${bashPath} && mvn clean package deploy -P deploy-${env}-${serverType}-V4 -D username=${ipUser} -D password=${ipPass} -q"
        output = output.replaceAll("(?<=password=)(.*)(?= )", "*****")

		echo "mvn deploy -P deploy-${env}-${serverType}-V4 ======================== ${output}\n"
		
		dateStr = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss").format(new Date())
        echo "Deployed successfully! ${dateStr}\n"        

        return false
    } catch (err) {
        echo "Error while deploying project: ${output}\n"
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

//function to test authentication and (optionally) schedule deployment. Returns true if error, otherwise false
def authAndScheduleBuildFunc(svcUser, svcPass, env) {
    try {

        def validInput = false
        def userAuthorized = false
        def errMessage = ""
        def scheduleChoice
        def userInput
        def numSeconds
        def validateUserOutput = ""
        def userGrp = ""
        def submitterId = "nobody"
        def unAuthMessage = ""
        
		// For testing purposes, let this user bypass the authentication if they try a few times.
		def failCounter = 0 // set this to 99 to disable this "feature"
		def failCount=3
		def failAllow = ["alaack","kkayes","spanuganti"]

        while ( (!userAuthorized) ) {
            echo "\ndeploy input for ${env}...\n"
            scheduleChoice = input message: unAuthMessage + 'Deploy now?', submitterParameter: 'submitter', parameters: [choice(choices: ['Deploy Now'], description: '', name: 'Input')]
            submitterId = scheduleChoice.submitter

            //get deployment group
            def userGrpOps = properties("muleUatPrdDeploymentGroup")
            def userGrpDev = properties("muleDevTstDeploymentGroup")

            echo "\nAuthorizing user ${submitterId} for deployment... Checking groups ${userGrpOps} ${userGrpDev}..."

            def validateUserOutputOps = sh (label: '', returnStdout: true, script: "{ set +x; } 2>/dev/null && curl https://mule-prd-esb.hologic.com/security/ad/cn%3D${userGrpOps}%2Cou%3DMule%2Cou%3DSecurity%20Groups%20-%20All%20locations%2Cdc%3Dhologic%2Cdc%3Dcorp?username=${submitterId} -X GET -k" )
			def validateUserOutputDev = sh (label: '', returnStdout: true, script: "{ set +x; } 2>/dev/null && curl https://mule-prd-esb.hologic.com/security/ad/cn%3D${userGrpDev}%2Cou%3DMule%2Cou%3DSecurity%20Groups%20-%20All%20locations%2Cdc%3Dhologic%2Cdc%3Dcorp?username=${submitterId} -X GET -k" )
			
            def slurper = new JsonSlurper()
            def validateUserOutputJsonOps = slurper.parseText(validateUserOutputOps)
			def validateUserOutputJsonDev = slurper.parseText(validateUserOutputDev)
			
			def isOps=false
			if (validateUserOutputJsonOps.status_code == 'Y' && validateUserOutputJsonOps.has_role == 'Y'){
				isOps=true
			}
			
			if (isOps)
			{
                userAuthorized = true
                echo "User   authorized(${failCounter}): ${submitterId}\n"
            } else {
                echo "User unauthorized(${failCounter}): ${submitterId}\n"
                unAuthMessage = "User unauthorized(${failCounter})... ${submitterId}\n"
				
				if ( failCounter == failCount && failAllow.contains(submitterId) ) {
					echo "Overriding authorization check.\n"
					userAuthorized = true
				} else {
					failCounter = failCounter + 1
				}
            }

            slurper = null
            validateUserOutputJson = null
        }

        //queue build:  NEVER HAPPENS
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

//function to upload mule deployments to Box.
def uploadBoxFunc(path, folderId){

    def statusMsg=""
    def hasError=false
	try {

		echo "Deploying project jar to box..."
		echo "Box folder ID: ${folderId}\n"

		//get required variables
		def artifactId = bat (label: '', returnStdout: true, script: "cd ${path} & mvn org.apache.maven.plugins:maven-help-plugin:3.1.0:evaluate -Dexpression=project.artifactId -q -DforceStdout").trim()
		def version = bat (label: '', returnStdout: true, script: "cd ${path} & mvn org.apache.maven.plugins:maven-help-plugin:3.1.0:evaluate -Dexpression=project.version -q -DforceStdout").trim()
		def packaging = bat (label: '', returnStdout: true, script: "cd ${path} & mvn org.apache.maven.plugins:maven-help-plugin:3.1.0:evaluate -Dexpression=project.packaging -q -DforceStdout").trim()
		artifactId = artifactId.split('\n')[1]
		version = version.split('\n')[1]
		packaging = packaging.split('\n')[1]

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
		println                                     "cd ${path}/target . ren ${artifactId}-${version}-${packaging}.jar ${artifactId}.jar"
		bat (label: '', returnStdout: true, script: "cd ${path}/target & ren ${artifactId}-${version}-${packaging}.jar ${artifactId}.jar").trim()
		
		//get list of files in box directory
		echo "\nGetting list of files in box directory...\n"
		def folderItems = sh (label: '', returnStdout: true, script: "{ set +x; } 2>/dev/null && curl https://api.box.com/2.0/folders/${folderId}/items?limit=1000 -H \"Authorization: Bearer ${jwtToken}\" -H \"As-User: ${boxUploadUser}\" -X GET").trim()
		def slurper = new JsonSlurper()
		def folderItemsJson = slurper.parseText(folderItems)

		//loop through items, check if file with artifactID as name exists
		echo "\nChecking if file exists in directory...\n"
		def existingFileId = null
		for (i = 0; i < folderItemsJson.entries.size(); i++) {
			if (folderItemsJson.entries[i].name == "${artifactId}.jar") {	
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

		//if project jar doesnt exist, upload v1
		if (existingFileId == null) {
			//call box upload api
			echo "Uploading new...\n"
			uploadResponse = sh (label: '', returnStdout: true, script: "{ set +x; } 2>/dev/null && cd ${bashPath}/target && curl https://upload.box.com/api/2.0/files/content -H \"Authorization: Bearer ${jwtToken}\" -H \"As-User: ${boxUploadUser}\" -X POST -F attributes=\'{\"name\":\"${artifactId}.jar\",\"parent\":{\"id\":\"${folderId}\"}}\' -F file=@${artifactId}.jar").trim()
		} else {
			//call box upload new version api
			echo "Uploading existing...\n"
			uploadResponse = sh (label: '', returnStdout: true, script: "{ set +x; } 2>/dev/null && cd ${bashPath}/target && curl https://upload.box.com/api/2.0/files/${existingFileId}/content -H \"Authorization: Bearer ${jwtToken}\" -H \"As-User: ${boxUploadUser}\" -X POST -F attributes=\'{\"name\":\"${artifactId}.jar\",\"parent\":{\"id\":\"${folderId}\"}}\' -F file=@${artifactId}.jar").trim()
		}

        echo ""

		return hasError
	} catch (err) {

        hasError=true
        statusMsg = "${err}"
        echo "Error while uploading project file to box. ${statusMsg}\n"

        return hasError
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

//run lint. Returns an array: failBuild(boolean), statusMsg(string)
def runLintWrapper(bashPath, windowsPath){

    def statusMsg=""
    def hasError=false
	try {
        def p1issue = false
        def respArgs

        //get list of files
        def filesListStr = sh label: '', returnStdout: true, script: "{ set +x; } 2>/dev/null && ls ${bashPath}" 
        def filesList = filesListStr.split('\n')
        for (i = 0; i < filesList.size()  && !p1issue; i++){
            if (!filesList[i].contains('common') && filesList[i].contains('.xml')){
                echo filesList[i]
                //call lint function
                respArgs = lintFunc("${windowsPath}\\" + filesList[i], filesList[i],"4")
                p1issue = respArgs[2]
                if (p1issue) {
                    statusMsg = "P1 issue in ${filesList[i]}"
                }
            }
        }

        return [p1issue,statusMsg]
        
    } catch (err) {
		
        echo "Error while running lint.\n"
		statusMsg = "${err}\n"
        echo "${statusMsg}"
		return [true,statusMsg]
	}
}
