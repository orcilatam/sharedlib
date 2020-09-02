package com.orcilatam.devops

class Stage {

	static String parsedProjectName = null
	static String parsedProjectVersion = null
	static String selectedRollbackTag = null


	static def waitForOK(script, seconds, callback) {
		def ok = true
		try {
			script.timeout(time: seconds, unit:'SECONDS') {
				script.input(message: "${script.STAGE_NAME}?", ok: "Ejecutar")
			}
		} catch(e) {
			ok = false // Cancelado o se pasó el tiempo
		} finally {
			if(ok) {
				callback.call()
			}
		}
	}


	static def waitForChoice(script, seconds, chooser, unattended, callback) {
		def selected = unattended
		try {
			script.timeout(time: seconds, unit:'SECONDS') {
				selected = chooser.call()
			}
		} catch(e) {
			selected = unattended // Cancelado o se pasó del tiempo
		} finally {
			script.echo "Ambiente de despliegue seleccionado: ${selected}"
			callback.call(selected)
		}
	}


	static def replacePlaceholder(script, filename, placeholder, value) {
		script.sh """set +x
			sed 's`{{ *${placeholder} *}}`${value}`g' -i "${filename}"
		"""
	}


	static def parseProjectParameters(script, projectFile) {
		if(projectFile == 'pom.xml') {
			def name = script.sh(
				script: "cat pom.xml | grep '<name>[A-Za-z_-]\\+</name>' | head -n 1 | sed 's/name>//g' | grep -o '[A-Za-z_-]\\+' || true",
				returnStdout: true
			).trim()
			if(name > "")
				Stage.parsedProjectName = name

			def version = script.sh(
				script: "cat pom.xml | grep '<version>[0-9\\.]\\+</version>' | head -n 1 | sed 's/version>//g' | grep -o '[0-9\\.]\\+' || true",
				returnStdout: true
			).trim()
			if(version > "")
				Stage.parsedProjectVersion = version
		}
	}


	static def buildMaven(script) {
		parseProjectParameters(script, 'pom.xml')  // Inicializa parsedProjectName y parsedProjectVersion
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


	static def runSonarQube(script, qualityGateKey = null) {
		script.sh '''set +x
			mvn \
			-Dmaven.compile.skip=true \
			-Dmaven.test.skip=true \
			install \
				org.sonarsource.scanner.maven:sonar-maven-plugin:3.6.0.1398:sonar \
				-Dsonar.host.url=http://sonarqube:9000
		'''

		// Consultar la API de SonarQube para obtener el resutado de la Quality Gate
		if(qualityGateKey != null) {
			script.sh """set +x
				# Esperar 30 segundos por el análisis
				sleep 30

				API=http://sonarqube:9000/api
				result=\$( curl \
					-s \$API/qualitygates/project_status?projectKey=${qualityGateKey} | \
					jq '.projectStatus.status' | tr -d '"' )

				[ "\$result" == "ERROR" ] && exit 1
				exit 0
			"""
		}
	}


	static def packageArtifact(script) {
		script.sh '''set +x
			mvn -Dmaven.compile.skip=true -Dmaven.test.skip=true package
		'''
	}


	static def uploadToArtifactory(script) {
		script.echo '*** Subiendo artefacto a Artifactory'
		script.withCredentials([script.usernamePassword(
			credentialsId: 'artifactory-deployment-user',
			usernameVariable: 'ARTIFACTORY_USERNAME',
			passwordVariable: 'ARTIFACTORY_PASSWORD'
		)]) {
			script.sh '''set +x
				mvn \
				-Dmaven.compile.skip=true \
				-Dmaven.test.skip=true \
				-Dmaven.package.skip=true \
				-Dmaven.install.skip=true \
				-Dartifactory.username="$ARTIFACTORY_USERNAME" \
				-Dartifactory.password="$ARTIFACTORY_PASSWORD" \
				deploy
			'''
		}
	}


	static def buildDockerImage(
		script, pushRegistry, projectName, projectVersion, pullFromSecureRegistry = false)
	{
		if(projectName ==~ /\{\{ *project.name *\}\}/ && Stage.parsedProjectName != null)
			projectName = Stage.parsedProjectName
		if(projectVersion ==~ /\{\{ *project.version *\}\}/ && Stage.parsedProjectVersion != null)
			projectVersion = Stage.parsedProjectVersion

		image = script.docker.build("$pushRegistry/$projectName:$projectVersion", "--pull .")
		script.docker.withRegistry("http://${pushRegistry}", 'registry-push-user') {
			image.push()
		}
	}


	static def scanWithClair(script, pushRegistry, projectName, projectVersion) {
		if(projectName ==~ /\{\{ *project.name *\}\}/ && Stage.parsedProjectName != null)
			projectName = Stage.parsedProjectName
		if(projectVersion ==~ /\{\{ *project.version *\}\}/ && Stage.parsedProjectVersion != null)
			projectVersion = Stage.parsedProjectVersion

		// TODO
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


	static def rollbackKubernetes(script, kubeConfig, namespace, yaml) {
		if(Stage.parsedProjectName != null)
			replacePlaceholder(script, yaml, 'project.name', Stage.parsedProjectName)
		if(Stage.selectedRollbackTag != null)
			replacePlaceholder(script, yaml, 'project.version', Stage.selectedRollbackTag)

		kubectlApply(script, kubeConfig, namespace, yaml, false)
	}


	static def selectDeploy(script, seconds, values, callback) {
		def unattended = values.get(0)
		for(value in values) {
			def v = value.toUpperCase()
			if(script.env.BRANCH_NAME == 'develop' && (v =~ "DEV" || v =~ "DESA")) {
				unattended = value
				break
			} else if(script.env.branch_name == 'release' && (v =~ "UAT" || v =~ "TEST" || v ~= "QA")) {
				unattended = value
				break
			} else if(script.env.branch_name == 'master' && (v =~ "PROD" || v =~ "PRD")) {
				unattended = value
				break
			}
		}
		waitForChoice(script, seconds, {
			script.input(
				message: "Seleccione ambiente:",
				ok: "Desplegar",
				parameters: [script.choice(
					name: 'DEPLOY',
					choices: values.join("\n"),
				)]
			)},
			unattended,
			{ selected -> callback.call(selected) }
		)
	}


    static def selectRollbackDockerImage(script, pushRegistry, projectName) {
		Stage.selectedRollbackTag = null

		parseProjectParameters(script, 'pom.xml')
		if(projectName ==~ /\{\{ *project.name *\}\}/ && Stage.parsedProjectName != null)
			projectName = Stage.parsedProjectName

		script.withCredentials([script.usernamePassword(
			credentialsId: 'registry-push-user',
			usernameVariable: 'REGISTRY_USERNAME',
			passwordVariable: 'REGISTRY_PASSWORD'
		)]) {
			def taglist = script.sh(
				returnStdout: true,	
				script: """set +x
					curl -sL \\
					-u "\${REGISTRY_USERNAME}:\${REGISTRY_PASSWORD}" \\
					https://${pushRegistry}/v2/${projectName}/tags/list | \\
					jq '.tags[]' | \\
					tr -d '"'
				"""
			).trim()
			if(taglist == "") {
				script.sh "false"
			}
			def tag = script.input(
				message: "Seleccione una imagen de  Docker:",
				ok: "Seleccionar",
				parameters: [script.choice(
					name: 'TAG',
					choices: taglist,
				)])
			if(tag > "")
				Stage.selectedRollbackTag = tag
		}
	}


	static def selectDeployToKubernetes(script, seconds, values, callback) {
		selectDeploy(script, seconds, values, callback)
	}

	static def selectRollback(script, seconds, values, callback) {
		selectDeploy(script, seconds, values, callback)
	}

	static def selectRollbackKubernetes(script, seconds, values, callback) {
		selectDeploy(script, seconds, values, callback)
	}

}
