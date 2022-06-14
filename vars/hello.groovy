import java.text.SimpleDateFormat
import java.util.Date
import java.time.*
import java.util.concurrent.TimeUnit
import groovy.json.JsonSlurper


def call(body) {

pipeline {
    agent any
    
    tools {
            maven 'Maven 3.6.0'
            // jdk 'JAVA_HOME'
            git 'Default'
        }

    stages {
        stage('Build') {
            steps {
                
                echo 'Building..'
                def bashPath ="/Users/sanjaykumarparui/AnypointStudio/studio-workspace/helloworld"
                sh "cd ${bashPath} && mvn clean package"
                sh "cd /Users/sanjaykumarparui/AnypointStudio/studio-workspace/helloworld && mvn clean package"
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
                //def bashPath = "/Users/sanjaykumarparui/AnypointStudio/studio-workspace/helloworld"
                
               // sh "cd /Users/sanjaykumarparui/AnypointStudio/studio-workspace/helloworld && mvn clean package deploy -DmuleDeploy"
                
        
            }
        }
    }
}

}   

