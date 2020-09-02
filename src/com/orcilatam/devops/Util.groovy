package com.orcilatam.devops

import java.io.File

class Util {

	static def shell(script, text) {
		if(script.isUnix()) {
			script.sh text
		} else {
			// Una conversión chimba o chanta de shell a Windows batch
			text = text
				.replaceAll("set \\+x", "")
				.replaceAll("\\\\", "^")
				.replaceAll("/", "\\\\")
				.replaceAll("rm -f", "del")
				.replaceAll("rm -rf", "rmdir /S /Q")
			script.bat text
		}
	}

	static def publishReport(script, report, name) {
		def path = new File(report)
		def directory = path.getParent()
		def html = path.getName()

		script.publishHTML target: [
			allowMissing: true,
			alwaysLinkToLastBuild: false,
			keepAll: true,
			reportDir: script.isUnix() ? directory : directory.replaceAll("/", "\\\\"),
			reportFiles: html,
			reportTitles: name,
			reportName: name
		]
	}

}
