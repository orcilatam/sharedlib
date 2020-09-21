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
			additionalArguments: "--scan ${target} -n --disableRetireJS true --format XML",
			skipOnScmChange: true,
			skipOnUpstreamChange: true)
		script.dependencyCheckPublisher(
			// Evitar status UNSTABLE
			unstableTotalHigh: 999,
			unstableTotalMedium: 999,
			unstableTotalLow: 999
			)
	}


	static def runSonarQube(script) {
		script.sh '''set +x
			mvn \
			-Dmaven.compile.skip=true \
			-Dmaven.test.skip=true \
			install \
				org.sonarsource.scanner.maven:sonar-maven-plugin:3.7.0.1746:sonar \
				-Dsonar.host.url=http://sonarqube:9000
		'''
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


	static def pushImageToArtifactory(script, pushRegistry) {
		script.docker.withRegistry("http://${pushRegistry}", 'registry-push-user') {
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


	static def installIngressController(script, kubeConfig, namespace, name, chart) {
		script.withKubeConfig([ credentialsId: kubeConfig, namespace: namespace ]) {
			script.sh """set +x
				helm install ${name} ${chart} --set controller.publishService.enabled=true
			"""
		}
	}

}
