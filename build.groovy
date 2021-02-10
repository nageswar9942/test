/**
Maven build
required parameteres: branchname,
                      POM_PATH(this is where the pom.xml loacated),
                      WORKSPACE(By default jenkins worksapce)
*/

import java.text.SimpleDateFormat
def codeCheckout(BRANCHNAME, module, scmURL){

    checkout([
      $class: 'GitSCM',
      branches: [[name: "*/${BRANCHNAME}"]],
      extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: module]],
      userRemoteConfigs: [[url: scmURL, credentialsId: configGitlabCredentialsId()]]
               ])
}


def codeChecks(parentPomDir, mavenSonarVariables){
    dir("${parentPomDir}"){
        withCredentials([usernamePassword(
                credentialsId: configArtifactoryCredentialsId(),
                usernameVariable: 'ARTIFACTORY_USER',
                passwordVariable: 'ARTIFACTORY_PASSWORD')]){
       // withCredentials([string(credentialsId: configSonarCredentialsId(), variable: 'sonar')]) {
            withSonarQubeEnv() {
               withMaven(maven: 'maven-3.6.0'){
                       sh (label: 'code Checks', script: "${mavenSonarVariables}")

              }
             }

                }
    }
}

def codeChecksOnJava11(parentPomDir, mavenSonarVariables){
    dir("${parentPomDir}"){
        def JAVA_HOME=""
           sh """ export JAVA_HOME
                  export PATH=${JAVA_HOME}/bin:$PATH
                  java -version """

        withCredentials([usernamePassword(
                credentialsId: configArtifactoryCredentialsId(),
                usernameVariable: 'ARTIFACTORY_USER',
                passwordVariable: 'ARTIFACTORY_PASSWORD')]){
            withSonarQubeEnv() {
               withMaven(maven: 'maven-3.6.0'){
                       sh (label: 'code Checks', script: "export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-11.0.9.11-2.el7_9.x86_64 && export PATH=$JAVA_HOME/bin:$PATH && java -version && ${mavenSonarVariables} && java -version")

              }
             }

                }
    }
}

def codeCheckOnAngularJs(module){
    dir("${module}"){
    env.NODEJS_HOME = "${tool 'Node 12.x'}"
    env.PATH="${env.NODEJS_HOME}/bin:${env.PATH}"
    sh 'npm install'

    }
    }

def codeQualityStatus(){
     timeout(time: 1, unit: 'HOURS'){
             waitForQualityGate abortPipeline: true
             }
    }




def codeBuilding(parentPomDir, mavenBuildVariables){
         dir("${parentPomDir}"){


             withCredentials([usernamePassword(
                credentialsId: configArtifactoryCredentialsId(),
                usernameVariable: 'ARTIFACTORY_USER',
                passwordVariable: 'ARTIFACTORY_PASSWORD')]){

                withMaven(maven: 'maven-3.6.0'){
                       sh (label: 'code compileing', script: "${mavenBuildVariables}")
                }

         }
         }
 }

def tagCreation(parentPomDir){
    dir("${parentPomDir}"){
             def pom = readMavenPom file: './pom.xml'
             print pom.parent.version
             def dateFormat = new SimpleDateFormat("yyyyMMddHHmm")
             def date = new Date()
             def date_format = dateFormat.format(date)
             println date_format

             withCredentials([usernamePassword(
                credentialsId: configGitlabCredentialsId(),
                usernameVariable: 'GITLAB_USER',
                passwordVariable: 'GITLAB_PASSWORD')]){
                    sh (label: 'Tag Creation', script: """
                      git config --global user.name "${GITLAB_USER}"
                      git config --global user.password "${GITLAB_PASSWORD}"
                       git config --list
                      git tag Release_${pom.parent.version}_${date_format}
                      git push origin Release_${pom.parent.version}_${date_format}
                    """)
                }
            }

}

def publishBinaryToArtifactory(artifactFilePath, artifactTargetFile, artifactTargetPath){

     def artifactoryURL = "${configArtifactoryBaseURL()}/${artifactTargetPath}"
     withCredentials([usernamePassword(
         credentialsId: configArtifactoryCredentialsId(),
         usernameVariable: 'ARTIFACTORY_USER',
         passwordVariable: 'ARTIFACTORY_KEY')]){
                sh ("curl -u${ARTIFACTORY_USER}:${ARTIFACTORY_KEY} -X PUT ${artifactoryURL}/${artifactTargetFile} -T ${artifactFilePath}")
         }
}


def imageBuild(module, version, dockerFilePath, env){
    dir("${dockerFilePath}"){
     withCredentials([usernamePassword(
         credentialsId: configArtifactoryCredentialsId(),
         usernameVariable: 'ARTIFACTORY_USER',
         passwordVariable: 'ARTIFACTORY_KEY')]){
            //sh("export DOCKER_BUILDKIT=1")
            def NOCACHE= sh(returnStdout: true, script:"echo \$((\$(date +%s%N)/1000000))").trim()
            print NOCACHE
            print env
            print version
            sh("export DOCKER_BUILDKIT=1 && docker build -t ${env}/track2/$module:$version --add-host=" + ' --build-arg ARTIFACTORY_USER=$ARTIFACTORY_USER --build-arg ARTIFACTORY_PASSWORD=$ARTIFACTORY_KEY' + " --build-arg NOCACHE=$NOCACHE .")

  }
    }
}

def dependentImageBuild(module, version, dockerFilePath){
    dir("${dockerFilePath}"){
     withCredentials([usernamePassword(
         credentialsId: configArtifactoryCredentialsId(),
         usernameVariable: 'ARTIFACTORY_USER',
         passwordVariable: 'ARTIFACTORY_KEY')]){
            //sh("export DOCKER_BUILDKIT=1")
            def NOCACHE= sh(returnStdout: true, script:"echo \$((\$(date +%s%N)/1000000))").trim()
            print NOCACHE
            sh("export DOCKER_BUILDKIT=1 && docker build -t ${module}:${version} --add-host=" + ' --build-arg ARTIFACTORY_USER=${ARTIFACTORY_USER} --build-arg ARTIFACTORY_PASSWORD=${ARTIFACTORY_KEY}' + " --build-arg NOCACHE=${NOCACHE} .")
  }
    }
}


def publishImage(module, version, env){
    dir("${module}"){
    def aws_login = sh (returnStdout: true, script: "gcloud auth configure-docker")
    print aws_login
    sh("docker tag image:tag image:tag")
    sh ("docker push image:tag")
  }
}

def publishDependentImage(module, version){
    dir("${module}"){
    def aws_login = sh (returnStdout: true, script: "gcloud auth configure-docker")
    print aws_login
    sh("docker tag image:tag image:tag")
    sh ("docker push image:tag")
  }
}

def configMapCreation(scriptDir, module, version, env){
    dir("${scriptDir}"){
      withKubeConfig([credentialsId: 'Dev-Kube-Config']) {
        sh "kustomize edit set image image=image:tag"
        sh "kustomize build . > kube-build.yaml"
        sh "kubectl apply -f kube-build.yaml"
      }

  }
}

def containerDeployment(scriptDir, module, version, env){
    dir("${scriptDir}"){
      withKubeConfig([credentialsId: 'Kube-Config']) {
        if (env == "dev"){
        sh "kubectl config use-context context"
        sh "kustomize edit set image iamge=iamge:tag"
        sh "kubectl apply -k ."
        } else if (env == "sit"){
         sh "kubectl config use-context context"
        sh "kustomize edit set image image=iamge:tag"
        sh "kubectl apply -k ."

        }
      }

  }
}

def codeDeployment(scriptPath, scriptFile, ansible_vars, WORKSPACE){
    dir("${scriptPath}"){
        withCredentials([usernamePassword(
                credentialsId: configArtifactoryCredentialsId(),
                usernameVariable: 'ARTIFACTORY_USER',
                passwordVariable: 'ARTIFACTORY_PASSWORD')]){
                    def vault_pass = vaultPass()
                    sh (label: 'creating password file', script: "echo ${vault_pass} > vault_pass")
                    //sh (label: '', script: "export ANSIBLE_HOST_KEY_CHECKING=False")
                   sh(label:'Deploying', script: "export ANSIBLE_HOST_KEY_CHECKING=False && ansible-playbook --vault-password-file vault_pass -i ${WORKSPACE}/Track2/inventory ${scriptFile} -e\"${ansible_vars} ARTIFACTORY_USER=${ARTIFACTORY_USER} ARTIFACTORY_PASSWORD=${ARTIFACTORY_PASSWORD}\" -vv")
    }
    }
}


def systemTesting(module, projectFile){
    SoapUIPro ( pathToTestrunner: 'C:\\Program Files\\SmartBear\\ReadyAPI-3.0.0\\bin\\testrunner.bat', pathToProjectFile: "${projectFile}", testSuite: '', testCase: '', testSuiteTags: '(tag1 || tag2) && !tag3', projectPassword: '', environment: '' )
}


def configArtifactoryBaseURL() {
  return env['ARTIFACTORY_BASE']
}


def configArtifactoryCredentialsId(){
      return 'ARTIFACTORY'
 }

 def configGitlabCredentialsId(){
      return 'GITLAB'
 }

 def configSonarCredentialsId(){
     return 'SONAR'
 }



def vaultPass(){
     return env['ANSIBLE_PASS']
 }



 return this
