//pipeline script specifically designed for maven parent pom
def call(body) {

    // evaluate the body block, and collect configuration into the object
    def pipelineParams= [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    //additional vars
    def skipRemaining = false

    //jenkins pipeline script
    pipeline {
        //specify agent
        agent any
        //specify tools
        tools {
            maven 'Maven'
            jdk 'JDK1.8'
            git 'Default'
        }
        //specify properties
        options {
            disableConcurrentBuilds()  
        }
        //deployment steps
        stages {
            //checkout git repo, check for diffs
            stage('Initialize') {
                steps {
                    git branch: "${pipelineParams.repoBranch}", credentialsId: '38fe9ead-483e-4219-85a7-378892c2e560', url: "https://github.hologic.com/${pipelineParams.githubOrg}/${pipelineParams.repoName}.git"
                    echo "workspace: ${workspace}/${pipelineParams.repoPath}"
                    script {
                        if ( !checkFolderForDiffsFunc("${workspace}/${pipelineParams.repoPath}") ) {
                            skipRemaining = true
                        }
                    }
                }
            }
            //deploy
            stage('Deploy Parent POM') {
                when {
                    expression {
                        !skipRemaining
                    }
                }
                steps {
                    echo "Deploying parent pom to maven repository.."
                    script {
                        try {
                            def output = bat label: '', returnStdout: true, script: "cd ${workspace}/${pipelineParams.repoPath} & mvn clean package deploy"
                            echo "${output}"
                        } catch (err) {
                            echo "Error while deploying project"
                            echo "${err}"
                            skipRemaining = true
                            currentBuild.result = 'FAILURE'
                        }
                    }
                    echo "Deployed successfully!"
                }
            }
        }
    }
}

/*********************************************************************************************************************************************************************
                                                                    Helper Functions
*********************************************************************************************************************************************************************/

//check git folder for differences
def checkFolderForDiffsFunc(path) {
    try {
        echo "Checking project for changes..."
        // git diff will return 1 for changes (failure) which is caught in catch, or 0 meaning no changes 
        def statusCode = bat label: '', returnStatus: true, script: "git diff --quiet --exit-code HEAD~1..HEAD ${path}"
        if (statusCode == 1) {
            echo "Changes made to project at path ${path}, continuing with build"
            return true
        } else {
            echo "Changes not made to project at path ${path}, exiting build"
            return false
        }
    } catch (err) {
        echo "Error while checking project for changes"
        echo "${err}"
        currentBuild.result = 'FAILURE'
        return false
    }
}