import java.text.SimpleDateFormat
import java.util.Date
import java.time.*
import java.util.concurrent.TimeUnit
import groovy.json.JsonSlurper


def call(body) {

pipeline {
    agent any

    stages {
        stage('Build') {
            steps {
                echo 'Building..'
            }
        }
        stage('Test') {
            steps {
                echo 'Testing..'
            }
        }
        stage('Deploy') {
            steps {
                echo 'Deploying....'
                def bashPath = "/Users/sanjaykumarparui/AnypointStudio/studio-workspace/helloworld"
                output = sh label: '', returnStdout: true, script: "{ set +x; } 2>/dev/null && cd ${bashPath} && mvn clean package deploy -P"
        
            }
        }
    }
}

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

}