import java.text.SimpleDateFormat
import java.util.Date
import java.time.*
import java.util.concurrent.TimeUnit
import groovy.json.JsonSlurper

print "curr class:" + this.getClass().getName()


//pipeline function  body org.jenkinsci.plugins.workflow.cps.CpsClosure2
def call(body) {

    println "body: " + body.getClass().getName()

    // evaluate the body block, and collect configuration into the object
    def pipelineParams= [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    //local vars
    def error = false
    def sendEmail = true
    def emailBody = ''
    def isStartedByUser
    def bashPath
    def folderDiffResult
    def bashJenkinsHome

    //jenkins pipeline script
    pipeline {
        //specify agent
        agent any
        //specify tools
        tools {
            jdk 'JDK1.8'
            git 'Default'
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
                    git branch: "${pipelineParams.repoBranch}", credentialsId: '38fe9ead-483e-4219-85a7-378892c2e560', url: "https://github.hologic.com/githubmulesoft/${pipelineParams.repoName}.git"
                    script {

                        println "\nChecking build trigger...\n" 
                        isStartedByUser = currentBuild.rawBuild.getCause(hudson.model.Cause$UserIdCause) != null
                        println "Build started by user? ${isStartedByUser}" 

                        //setting bash path
                        bashPath = workspace.replaceAll("\\\\", "/")
		                bashPath = bashPath.replaceAll('C:', '/c')

                        bashJenkinsHome = JENKINS_HOME.replaceAll("\\\\", "/")
                        bashJenkinsHome = bashJenkinsHome.replaceAll('C:', '/c')
						
						folderDiffResult = pipelineParams.Environments.split(',') as List


// TEMPORARILY, DON"T CARE HOW THE BUILD WAS STARTED
if (1 == 0) {
                        // if build triggered by commit
                        if (!isStartedByUser){
                            println "\nChecking modified property files in most recent commit...\n"
                        } // if build triggered by user 
                        else {
							println "\nTriggered by user...\n"
                            //select which env to deploy to (based on pipeline param envs)
                            def envsList = pipelineParams.Environments.split(',') as List
                            //def selectedEnv = input message: 'Select deployment environment: ', submitterParameter: 'submitter', parameters: [choice(choices: envsList, description: '', name: 'Input')]
                            //def selectedServer = input message: 'Select deployment server cluster: ', submitterParameter: 'submitter', parameters: [choice(choices: ['ESB', 'API', 'Both'], description: '', name: 'Input')]
                            //folderDiffResult = []
                            //if (selectedServer.Input == 'Both'){
                            //    folderDiffResult.add('di' + selectedEnv.Input.toLowerCase() + ' api')
                            //    folderDiffResult.add('di' + selectedEnv.Input.toLowerCase() + ' esb')
                            //} else {
                            //    folderDiffResult.add('di' + selectedEnv.Input.toLowerCase() + ' ' + selectedServer.Input.toLowerCase())
                            //}
                        }
} // TEMPORARY....................

                    }
                }
            }
            stage ('Deploy Properties') {
                when {
                    expression {
                        !error
                    }
                }
                steps {
                    println "Deploying properties to specified environments...\n"
                    script {
						
						isStartedByUser = currentBuild.rawBuild.getCause(hudson.model.Cause$UserIdCause) != null
						
                        //loop thru environments, call deploy func for each
                        def authString = ''
                        for (i = 0; i < folderDiffResult.size(); i++){
						
							// == Get Approval for specific Environment ==
                            def strippedEnv = folderDiffResult[i].toUpperCase().substring(2, 5)
                            def authorized = false
							
							println "Checking ${strippedEnv}...\n"
														
                            if ((strippedEnv == 'UAT'||strippedEnv == 'PRD') && !authorized){
								if (isStartedByUser == true) {
									authString = scheduleBuildFunc(strippedEnv);
									if (authString == 'authorized'){
										authorized = true
									}
								} else {
									println "Deploying to UAT or PRD requires user-initiated build.\n"
								}
                            } else {
                                authorized = true
                            }
							
                            // ==== deploy properties ===== 
                            if (authorized == true){
								println "Deploying prop files..."
                                println "Deploying property file: ${folderDiffResult[i]}"
                                def deployResult = deployFunc(bashPath, bashJenkinsHome, folderDiffResult[i])
                                if (deployResult == 'E'){
                                    error = true
                                    break
                                }
                            } else {
								error = true
								sendEmail = false
							}
                        } // FOR
                    } // SCRIPT
                } // STEPS
            } // STAGE
            stage ('Build Handling') {
                steps {
                    script {
                        if (error && sendEmail){
                            println "Errors occured during build, sending email notification\n"

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
                        println "Build finished, exiting.\n"
                    }
                }
            }
        }
    }
}

/*********************************************************************************************************************************************************************
                                                                    Helper Functions
*********************************************************************************************************************************************************************/
def properties(str) {
	def pp=new properties();
	return pp.call(str);
}

//check git folder for differences
def checkFolderForDiffsFunc(path, envs) {
    try {

    	def xx = new HolxVars()
	
        println "Checking hologic domain for changes... Modified files:"
        
        def modifiedFilesStr = "hologic-esb-domain/esb-properties/didev/secure-HolxDomain.properties"
		if (xx.debugViaSh == 0) {
			modifiedFilesStr = sh label: '', returnStdout: true, script: "{ set +x; } 2>/dev/null && cd ${path} && git show --pretty=\"\" --name-only HEAD"
		}
        println "${modifiedFilesStr}"

        def modifiedFilesList = modifiedFilesStr.split('\n')
        def deployCommandList = []

        //loop thru modified files
        for (i = 0; i < modifiedFilesList.size(); i++) {
            //check if current file is a properties file
            def currentStr = modifiedFilesList[i]
            if (currentStr.endsWith('properties')){

				def envsList=envs.split(',')
				for (j = 0 ; j < envsList.size() ; j++) {
					def currentEnv=envsList[j].toLowerCase()
					//if current env is not included in list of envs to deploy -> skip, otherwise add to list
					if (!currentStr.contains("/di"+currentEnv+"/")) {
						continue
					} else {
						deployCommandList.add(currentEnv.toUpperCase())
					}
				}
			}
        }

        return deployCommandList
        
    } catch (err) {
        println "Error while checking project for changes\n"
        println "${err}\n"
        return 'E'
    }
}

//deploy property files using propdeploy script
def deployFunc(path, jenkinsHome, envCommand) {
    try {

        //run propdeploy script   ${envCommand}
	println "starting... scripts/propdeploy.sh ${envCommand}\n"
        //def deployResult = sh label: '', returnStdout: true, script: "{ set +x; } 2>/dev/null && cd ${path} && ./scripts/propdeploy.sh ${envCommand}"
        def deployResult = sh label: '', returnStdout: true, script: "cd ${path} && ./scripts/propdeploy.sh ${envCommand}"
        println "Deployed successfully! ${deployResult}\n"

        return 'S'
    } catch (err) {
        println "Error while deploying to: ${envCommand}, exiting build"
        println "${err}\n"
        return 'E'
    }
}


//function to schedule deployment
def scheduleBuildFunc(env) {
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
        def authString = ""
        
		// For testing purposes, let this user bypass the authentication if they try a few times.
		def failCounter = 0
		def failCount=3   // set this to -1 to disable this "feature"
		def failAllow = ["alaack","kkayes","spanuganti"]

        while ( (!userAuthorized) ) {
            echo "\nSchedule or deploy input for ${env}...\n"
            scheduleChoice = input message: unAuthMessage + 'Schedule or deploy now?', submitterParameter: 'submitter', parameters: [choice(choices: ['Deploy Now', 'Schedule'], description: '', name: 'Input')]
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
            if ( env == 'UAT' || env == 'PRD' ){ // REMOVED on 11/16/2021 AL: env == 'UAT' ||
                userGrp = properties("muleUatPrdDeploymentGroup")
            } else {
                userGrp = properties("muleDevTstDeploymentGroup")
            }

            echo "\nAuthorizing user ${submitterId} for deployment... Checking group ${userGrp}..."
            //echo "\ncalling: https://mulprdesb.hologic.com/security/ad/cn%3D${userGrp}%2Cou%3DMule%2Cou%3DSecurity%20Groups%20-%20All%20locations%2Cdc%3Dhologic%2Cdc%3Dcorp?username=${submitterId}..."
            

            validateUserOutput = sh (label: '', returnStdout: true, script: "{ set +x; } 2>/dev/null && curl https://mule-prd-esb.hologic.com/security/ad/cn%3D${userGrp}%2Cou%3DMule%2Cou%3DSecurity%20Groups%20-%20All%20locations%2Cdc%3Dhologic%2Cdc%3Dcorp?username=${submitterId} -X GET -k" )

            def slurper = new JsonSlurper()
            echo "\ngot: ${validateUserOutput}"
            def validateUserOutputJson = slurper.parseText(validateUserOutput)

            if (validateUserOutputJson.status_code == 'Y' && validateUserOutputJson.has_role == 'Y'){
                userAuthorized = true
                echo "User   authorized(${failCounter}): ${submitterId}\n"
            } else {
                if (env=="UAT") {  // 2022-01-26 Allow anyone to deploy to UAT
                    userAuthorized=true;
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
        if (userAuthorized) {
        	authString = "authorized"
        }
        return authString
    } catch (err) {
        echo "Error while scheduling deployment\n"
        echo "${err}\n"
        return true
    }
}
