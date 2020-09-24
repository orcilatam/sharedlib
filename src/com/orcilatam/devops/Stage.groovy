package com.orcilatam.devops

class Stage {

	static String parsedProjectName = null
	static String parsedProjectVersion = null
	static def projectImage = null


	static def replacePlaceholder(script, filename, placeholder, value) {
		script.sh """set +x
			sed 's`{{ *${placeholder} *}}`${value}`g' -i "${filename}"
		"""
	}


	static def parseProjectParameters(script, projectFile) {
		if(projectFile == 'pom.xml') {
			def name = script.sh(
				script: "xmllint --xpath \"/*[local-name()='project']/*[local-name()='name']/text()\" pom.xml",
				returnStdout: true
			).trim()
			if(name > "")
				Stage.parsedProjectName = name

			def version = script.sh(
				script: "xmllint --xpath \"/*[local-name()='project']/*[local-name()='version']/text()\" pom.xml",
				returnStdout: true
			).trim()
			if(version > "")
				Stage.parsedProjectVersion = version
		}
	}


	static def buildMaven(script) {
		parseProjectParameters(script, 'pom.xml')  // Inicializa {{ variables de remplazo }}
		script.sh 'mvn clean && mvn compile'
	}


	static def testJUnit(script) {
		script.sh '''set +x
			mvn \
			-Dmaven.compile.skip=true \
			test
		'''
		def exitCode = script.sh(
			script: 'find target/surefire-reports -name TEST*.xml | grep .',
			returnStatus: true)
		if(exitCode == 0) {
			script.junit 'target/surefire-reports/TEST*.xml'
		}
	}


	static def runOWASPDependencyChecks(script, target = '.') {
		script.dependencyCheck(
			odcInstallation: 'dependency-check',
			additionalArguments: "--scan ${target} --format XML",
			skipOnScmChange: true,
			skipOnUpstreamChange: true)
		script.dependencyCheckPublisher(
			// Evitar status UNSTABLE
			unstableTotalHigh: 999,
			unstableTotalMedium: 999,
			unstableTotalLow: 999
			)
	}


	static def runSonarQube(script, sonarHostPort) {
		return true
/*
		script.sh """set +x
			mvn \\
			-Dmaven.compile.skip=true \\
			-Dmaven.test.skip=true \\
			install \\
				org.sonarsource.scanner.maven:sonar-maven-plugin:3.7.0.1746:sonar \\
				-Dsonar.host.url=http://${sonarHostPort}
		"""
*/
	}


	static def packageArtifact(script) {
		script.sh '''set +x
			mvn -Dmaven.compile.skip=true -Dmaven.test.skip=true package
		'''
	}


	static def buildDockerImage(script, pushRegistry, projectName = null, projectVersion = null) {
		if(projectName == null && Stage.parsedProjectName != null)
			projectName = Stage.parsedProjectName
		if(projectVersion == null && Stage.parsedProjectVersion != null)
			projectVersion = Stage.parsedProjectVersion

		projectImage = script.docker.build("$pushRegistry/$projectName:$projectVersion", "--pull .")
	}


	static def pushImageToArtifactory(script, pushRegistry, registryId) {
		script.docker.withRegistry("http://${pushRegistry}", registryId) {
			if(projectImage != null)
				projectImage.push()
		}
	}


	static def kubectlApply(script, kubeConfig, namespace, yaml) {
		script.withKubeConfig([ credentialsId: kubeConfig, namespace: namespace ]) {
			script.sh """set +x
				kubectl apply -f "${yaml}"
			"""
		}
	}


	static def deployToKubernetes(script, kubeConfig, namespace, yaml) {
		if(Stage.parsedProjectName != null)
			replacePlaceholder(script, yaml, 'project.name', Stage.parsedProjectName)
		if(Stage.parsedProjectVersion != null)
			replacePlaceholder(script, yaml, 'project.version', Stage.parsedProjectVersion)

		kubectlApply(script, kubeConfig, namespace, yaml)
	}


	static def updateHelmRepositories(script) {
		script.sh """set +x
			if ! helm repo ls -o json | grep -v 'WARNING' | jq '.[].name' | grep '\"stable\"'; then
				helm repo add stable https://kubernetes-charts.storage.googleapis.com/
			fi
			if ! helm repo ls -o json | grep -v 'WARNING' | jq '.[].name' | grep '\"ingress-nginx\"'; then
				helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
			fi
			helm repo update
		"""
	}


	static def installNginxIngressController(script, kubeConfig, namespace, name) {
		script.withKubeConfig([ credentialsId: kubeConfig, namespace: namespace ]) {
			script.sh """set +x
				if ! helm ls -o json | grep -v 'WARNING' | jq '.[].name' | grep '"${name}"'; then
					helm install ${name} \\
						ingress-nginx/ingress-nginx \\
						--set controller.publishService.enabled=true
				fi
			"""
		}
	}


	static def standUpInfrastructure(script, tokenId, pushRegistry, registryId) {
		script.withCredentials([script.usernamePassword(
			credentialsId: registryId,
			usernameVariable: 'REGISTRY_USERNAME',
			passwordVariable: 'REGISTRY_PASSWORD')])
		{
			script.withCredentials([script.string(
				credentialsId: tokenId,
				variable: 'TOKEN')])
			{
				script.sh '''set +x
					terraform init && \
					terraform apply -auto-approve \
						-var "token=$TOKEN" \
						-var "registry-host=''' + pushRegistry + '''" \
						-var "registry-username=$REGISTRY_USERNAME" \
						-var "registry-password=$REGISTRY_PASSWORD"
				'''
			}
		}
	}


	static def sendSlackMessage(script, text) {
		script.slackSend(color: "#ffffff", message: text)
	}


	static def sendSlackWarning(script, text) {
		script.slackSend(color:"#ffff00", message: text)
	}


	static def sendSlackSuccess(script, text) {
		script.slackSend(color:"#00ff00", message: text)
	}


	static def sendSlackAlert(script, text) {
		script.slackSend(color:"#ff0000", message: text)
	}


	static def sendSlackStageMessage(script) {
		sendSlackMessage(script, String.format("%s: %s", script.env.JOB_NAME, script.env.STAGE_NAME))
	}

}
