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
                    git branch: "master", credentialsId: '38fe9ead-483e-4219-85a7-378892c2e560', url: "https://github.hologic.com/githubmulesoft/hologic-domain.git"
                    script {

                        echo "\nChecking build trigger...\n" 
                        isStartedByUser = currentBuild.rawBuild.getCause(hudson.model.Cause$UserIdCause) != null
                        echo "Build started by user? ${isStartedByUser}" 

                        //setting bash path
                        bashPath = workspace.replaceAll("\\\\", "/")
		                bashPath = bashPath.replaceAll('C:', '/c')

                        bashJenkinsHome = JENKINS_HOME.replaceAll("\\\\", "/")
                        bashJenkinsHome = bashJenkinsHome.replaceAll('C:', '/c')

                        // if build triggered by commit
                        if (!isStartedByUser){
                            echo "\nChecking modified property files in most recent commit...\n"
                            folderDiffResult = checkFolderForDiffsFunc(bashPath, pipelineParams.Environments)
                            if (folderDiffResult == 'E') {
                                echo "Error while checking hologic domain for property file updates, exiting build.\n"
                                error = true
                            } else {
                                if (folderDiffResult.size() > 0){
                                    echo "Property files updated, continuing with build...\n"
                                } else {
                                    echo "No property file updates in most recent commit, exiting build.\n"
                                    error = true
                                    sendEmail = false
                                }
                            }
                        } // if build triggered by user 
                        else {
                            //select which env to deploy to (based on pipeline param envs)
                            def envsList = pipelineParams.Environments.split(',') as List
                            def selectedEnv = input message: 'Select deployment environment: ', submitterParameter: 'submitter', parameters: [choice(choices: envsList, description: '', name: 'Input')]
                            def selectedServer = input message: 'Select deployment server cluster: ', submitterParameter: 'submitter', parameters: [choice(choices: ['ESB', 'API', 'Both'], description: '', name: 'Input')]
                            folderDiffResult = []
                            if (selectedServer.Input == 'Both'){
                                folderDiffResult.add('di' + selectedEnv.Input.toLowerCase() + ' api')
                                folderDiffResult.add('di' + selectedEnv.Input.toLowerCase() + ' esb')
                            } else {
                                folderDiffResult.add('di' + selectedEnv.Input.toLowerCase() + ' ' + selectedServer.Input.toLowerCase())
                            }
                        }
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
                    echo "Deploying properties to specified environments...\n"
                    script {
                        //loop thru environments, call deploy func for each
                        def uatPreAuth = false
                        def prdPreAuth = false
                        def authString = ''
                        for (i = 0; i < folderDiffResult.size(); i++){
                            //schedule build if uat or prd
                            def strippedEnv = folderDiffResult[i].toUpperCase().substring(2, 5)
                            def authorized = false
                            if (strippedEnv == 'UAT' && !uatPreAuth){
                                authString = scheduleBuildFunc(strippedEnv);
                                if (authString == 'authorized'){
                                    authorized = true
                                    uatPreAuth = true
                                }
                            } else if (strippedEnv == 'PRD' && !prdPreAuth){
                                authString = scheduleBuildFunc(strippedEnv);
                                if (authString == 'authorized'){
                                    authorized = true
                                    prdPreAuth = true
                                }
                            } else {
                                authorized = true
                            }
                            //deploy properties
                            if (authorized == true){
                                echo "Deploying property file: ${folderDiffResult[i]}"
                                def deployResult = deployFunc(bashPath, bashJenkinsHome, folderDiffResult[i])
                                if (deployResult == 'E'){
                                    error = true
                                    break
                                }
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

//check git folder for differences
def checkFolderForDiffsFunc(path, envs) {
    try {
        echo "Checking hologic domain for changes... Modified files:"
        
        def modifiedFilesStr = sh label: '', returnStdout: true, script: "{ set +x; } 2>/dev/null && cd ${path} && git show --pretty=\"\" --name-only HEAD"
        echo "${modifiedFilesStr}"

        def modifiedFilesList = modifiedFilesStr.split('\n')
        def deployCommandList = []

        //loop thru modified files
        for (i = 0; i < modifiedFilesList.size(); i++) {
            //check if current file is a properties file
            def currentStr = modifiedFilesList[i]
            if (currentStr.contains('secure-HolxDomain-di')){
                //get environment
                def env = currentStr.substring(currentStr.indexOf('.properties') - 5, currentStr.indexOf('.properties'))

                //if current env is not included in list of envs to deploy -> skip, otherwise add to list
                if (!envs.toLowerCase().contains(env.toLowerCase().substring(2))){
                    continue
                } else {
                    deployCommandList.add(env)
                }
                
                //check if its an API properties file
                if (currentStr.contains('gateway-properties')){
                    deployCommandList[deployCommandList.size() - 1] = deployCommandList[deployCommandList.size() - 1] + ' api'
                } else {
                    deployCommandList[deployCommandList.size() - 1] = deployCommandList[deployCommandList.size() - 1] + ' esb'
                }
            }
        }

        return deployCommandList
        
    } catch (err) {
        echo "Error while checking project for changes\n"
        echo "${err}\n"
        return 'E'
    }
}

//deploy property files using propdeploy script
def deployFunc(path, jenkinsHome, envCommand) {
    try {

        //run propdeploy script
        def deployResult = sh label: '', returnStdout: true, script: "{ set +x; } 2>/dev/null && cd ${path} && ./propdeploy.sh ${envCommand}"
        echo "Deployed successfully!\n"

        return 'S'
    } catch (err) {
        echo "Error while deploying to: ${envCommand}, exiting build"
        echo "${err}\n"
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
        def submitterId
        def unAuthMessage = ""
        def authString = ""
        

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
                authString = 'authorized'
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

        return authString
    } catch (err) {
        echo "Error while scheduling deployment\n"
        echo "${err}\n"
        return true
    }
}
