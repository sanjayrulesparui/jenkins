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

}