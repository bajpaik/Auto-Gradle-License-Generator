package com.iamutkarshtiwari.licensetools

import groovy.json.JsonBuilder
import groovy.util.slurpersupport.GPathResult
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ResolvedArtifact
import org.xml.sax.helpers.DefaultHandler
import org.yaml.snakeyaml.Yaml

class LicenseToolsPlugin implements Plugin<Project> {

    final yaml = new Yaml()

    final DependencySet librariesYaml = new DependencySet() // based on libraries.yml
    final DependencySet dependencyLicenses = new DependencySet() // based on license plugin's dependency-license.xml

    @Override
    void apply(Project project) {
        project.extensions.add(LicenseToolsExtension.NAME, LicenseToolsExtension)

        def checkLicenses = project.task('checkLicenses').doLast {
            initialize(project)

            LicenseToolsExtension ext = project.extensions.findByType(LicenseToolsExtension)

            def notDocumented = dependencyLicenses.notListedIn(librariesYaml)
            def notInDependencies = librariesYaml.notListedIn(dependencyLicenses)

            if (notDocumented.empty && notInDependencies.empty) {
                project.logger.info("checkLicenses: ok")
                return
            }

            // Clean the file for complete rewrite
            cleanYaml(project.file(ext.licensesYaml))


            def outFile = ext.licensesYaml
            def writer = outFile.newWriter()

            // Rewrite all the existing libraries
            if (librariesYaml.size() > 0) {
                librariesYaml.each { libraryInfo ->
                    if (!notInDependencies.contains(libraryInfo.getArtifactId())) {
                        appendToWriter(libraryInfo, writer)
                    }
                }
            }

            // Add the licenses that are not listed
            if (notDocumented.size() > 0) {
                project.logger.warn("# Licenses for libraries not listed in ${ext.licensesYaml}: are being added now...")
                notDocumented.each { libraryInfo ->
                    appendToWriter(libraryInfo, writer)
                }
            }
            writer.close()
        }

        checkLicenses.configure {
            group = "Verification"
            description = 'Check whether dependency licenses are listed in licenses.yml'
        }

        def generateLicensePage = project.task('generateLicensePage').doLast {
            initialize(project)
            generateLicensePage(project)
        }

        generateLicensePage.dependsOn('checkLicenses')

        def generateLicenseJson = project.task('generateLicenseJson').doLast {
            initialize(project)
            generateLicenseJson(project)
        }
        generateLicenseJson.dependsOn('checkLicenses')

        project.tasks.findByName("check").dependsOn('checkLicenses')
    }

    void initialize(Project project) {
        LicenseToolsExtension ext = project.extensions.findByType(LicenseToolsExtension)
        loadLibrariesYaml(project.file(ext.licensesYaml))
        loadDependencyLicenses(project, ext.ignoredGroups, ext.ignoredProjects)
    }

    /**
     * Clean the file for rewrite
     * @param yamlFile
     */
    void cleanYaml(File yamlFile) {
        def outFile = yamlFile
        def writer = outFile.newWriter()
        writer << ""
        writer.close()
    }

    /**
     * Append the library info to the writer
     * @param libraryInfo
     * @param writer
     */
    void appendToWriter(LibraryInfo libraryInfo, Writer writer) {
        writer.append("- artifact: ${libraryInfo.artifactId.withWildcardVersion()}\n")
        writer.append("  name: ${libraryInfo.escapedName ?: "#NAME#"}\n")

        if (libraryInfo.getCopyrightHolder() == null || libraryInfo.getCopyrightHolder().length() == 0) {
            writer.append("  copyrightHolder: ${libraryInfo.copyrightHolder ?: "#COPYRIGHT_HOLDER#"}\n")
        } else {
            writer.append("  copyrightHolder: ${libraryInfo.getCopyrightHolder()}\n")
        }

        if (libraryInfo.getYear() == null || libraryInfo.getYear().length() == 0) {
            writer.append("  year: ${libraryInfo.year ?: "#YEAR#"}\n")
        } else {
            writer.append("  year: ${libraryInfo.getYear()}\n")
        }

        if (libraryInfo.getLicense() == null || libraryInfo.getLicense().length() == 0) {
            writer.append("  license: ${libraryInfo.license ?: "#LICENSE#"}\n")
        } else {
            writer.append("  license: ${libraryInfo.getLicense()}\n")
        }

        if (libraryInfo.getLicenseUrl() == null || libraryInfo.getLicenseUrl().length() == 0) {
            writer.append("  licenseUrl: ${libraryInfo.licenseUrl ?: "#LICENSEURL#"}\n")
        } else {
            writer.append("  licenseUrl: ${libraryInfo.getLicenseUrl()}\n")
        }

        if (libraryInfo.getUrl() == null || libraryInfo.getUrl().length() == 0) {
            writer.append("  url: ${libraryInfo.url ?: "#URL#"}\n")
        } else {
            writer.append("  url: ${libraryInfo.getUrl()}\n")
        }

        if (libraryInfo.getSkip()) {
            writer.append("  skip: ${libraryInfo.getSkip()}\n")
        }
    }

    void loadLibrariesYaml(File licensesYaml) {
        if (!licensesYaml.exists()) {
            return
        }

        def libraries = loadYaml(licensesYaml)
        for (lib in libraries) {
            def libraryInfo = LibraryInfo.fromYaml(lib)
            librariesYaml.add(libraryInfo)
        }
    }

    void loadDependencyLicenses(Project project, Set<String> ignoredGroups, Set<String> ignoredProjects) {
        resolveProjectDependencies(project, ignoredProjects).each { d ->
            if (d.moduleVersion.id.version == "unspecified") {
                return
            }
            if (ignoredGroups.contains(d.moduleVersion.id.group)) {
                return
            }

            def dependencyDesc = "$d.moduleVersion.id.group:$d.moduleVersion.id.name:$d.moduleVersion.id.version"

            def libraryInfo = new LibraryInfo()
            libraryInfo.artifactId = ArtifactId.parse(dependencyDesc)
            libraryInfo.filename = d.file
            dependencyLicenses.add(libraryInfo)

            Dependency pomDependency = project.dependencies.create("$dependencyDesc@pom")
            Configuration pomConfiguration = project.configurations.detachedConfiguration(pomDependency)

            pomConfiguration.resolve().each {
                project.logger.info("POM: ${it}")
            }

            File pStream
            try {
                pStream = pomConfiguration.resolve().asList().first()
            } catch (Exception e) {
                project.logger.warn("Unable to retrieve license for $dependencyDesc")
                return
            }

            XmlSlurper slurper = new XmlSlurper(true, false)
            slurper.setErrorHandler(new DefaultHandler())
            GPathResult xml = slurper.parse(pStream)

            libraryInfo.libraryName = xml.name.text()
            libraryInfo.url = xml.url.text()

            xml.licenses.license.each {
                if (!libraryInfo.license) {
                    // takes the first license
                    libraryInfo.license = it.name.text().trim()
                    libraryInfo.licenseUrl = it.url.text().trim()
                }
            }
        }
    }

    Map<String, ?> loadYaml(File yamlFile) {
        return yaml.load(yamlFile.text) as Map<String, ?> ?: [:]
    }

    void generateLicensePage(Project project) {
        def ext = project.extensions.getByType(LicenseToolsExtension)

        def noLicenseLibraries = new ArrayList<LibraryInfo>()
        def content = new StringBuilder()

        librariesYaml.each { libraryInfo ->
            if (libraryInfo.skip) {
                project.logger.info("generateLicensePage: skip ${libraryInfo.name}")
                return
            }

            // merge dependencyLicenses's libraryInfo into librariesYaml's
            def o = dependencyLicenses.find(libraryInfo.artifactId)
            if (o) {
                libraryInfo.license = libraryInfo.license ?: o.license
                libraryInfo.filename = o.filename
                libraryInfo.artifactId = o.artifactId
                libraryInfo.url = libraryInfo.url ?: o.url
            }
            try {
                content.append(Templates.buildLicenseHtml(libraryInfo))
            } catch (NotEnoughInformationException e) {
                noLicenseLibraries.add(e.libraryInfo)
            }
        }

        assertEmptyLibraries(noLicenseLibraries)

        def assetsDir = project.file("src/main/assets")
        if (!assetsDir.exists()) {
            assetsDir.mkdirs()
        }

        project.logger.info("render ${assetsDir}/${ext.outputHtml}")
        project.file("${assetsDir}/${ext.outputHtml}").write(Templates.wrapWithLayout(content))
    }

    void generateLicenseJson(Project project) {
        def ext = project.extensions.getByType(LicenseToolsExtension)
        def noLicenseLibraries = new ArrayList<LibraryInfo>()

        def json = new JsonBuilder()
        def librariesArray = []

        librariesYaml.each { libraryInfo ->
            if (libraryInfo.skip) {
                project.logger.info("generateLicensePage: skip ${libraryInfo.name}")
                return
            }

            // merge dependencyLicenses's libraryInfo into librariesYaml's
            def o = dependencyLicenses.find(libraryInfo.artifactId)
            if (o) {
                libraryInfo.license = libraryInfo.license ?: o.license
                // libraryInfo.filename = o.filename
                libraryInfo.artifactId = o.artifactId
                libraryInfo.url = libraryInfo.url ?: o.url
            }
            try {
                Templates.assertLicenseAndStatement(libraryInfo)
                librariesArray << libraryInfo
            } catch (NotEnoughInformationException e) {
                noLicenseLibraries.add(e.libraryInfo)
            }
        }

        assertEmptyLibraries(noLicenseLibraries)

        def assetsDir = project.file("src/main/assets")
        if (!assetsDir.exists()) {
            assetsDir.mkdirs()
        }

        json {
            libraries librariesArray.collect {
                l ->
                    return [
                        notice: l.notice,
                        copyrightHolder: l.copyrightHolder,
                        copyrightStatement: l.copyrightStatement,
                        license: l.license,
                        licenseUrl: l.licenseUrl,
                        normalizedLicense: l.normalizedLicense,
                        year: l.year,
                        url: l.url,
                        libraryName: l.libraryName,
                        // I don't why artifactId won't serialize, and this is the only way
                        // I've found -- vishna
                        artifactId: [
                                name: l.artifactId.name,
                                group: l.artifactId.group,
                                version: l.artifactId.version,
                        ]
                    ]
            }
        }

        project.logger.info("render ${assetsDir}/${ext.outputJson}")
        project.file("${assetsDir}/${ext.outputJson}").write(json.toString())
    }

    static void assertEmptyLibraries(ArrayList<LibraryInfo> noLicenseLibraries) {
        if (noLicenseLibraries.empty) return
        StringBuilder message = new StringBuilder()
        message.append("Not enough information for:\n")
        message.append("---\n")
        noLicenseLibraries.each { libraryInfo ->
            message.append("- artifact: ${libraryInfo.artifactId}\n")
            message.append("  name: ${libraryInfo.name}\n")
            if (!libraryInfo.license) {
                message.append("  license: #LICENSE#\n")
            }
            if (!libraryInfo.copyrightStatement) {
                message.append("  copyrightHolder: #AUTHOR# (or authors: [...])\n")
                message.append("  year: #YEAR# (optional)\n")
            }
        }
        throw new RuntimeException(message.toString())
    }

    // originated from https://github.com/hierynomus/license-gradle-plugin DependencyResolver.groovy
    Set<ResolvedArtifact> resolveProjectDependencies(Project project, Set<String> ignoredProjects) {
        def subprojects = project.rootProject.subprojects.findAll { Project p -> !ignoredProjects.contains(p.name) }
                .groupBy { Project p -> "$p.group:$p.name:$p.version" }

        List<ResolvedArtifact> runtimeDependencies = []

        project.rootProject.subprojects.findAll { Project p -> !ignoredProjects.contains(p.name) }.each { Project subproject ->
            runtimeDependencies << subproject.configurations.all.findAll { Configuration c ->
                // compile|implementation|api, release(Compile|Implementation|Api), releaseProduction(Compile|Implementation|Api), and so on.
                c.name.matches(/^(?:release\w*)?([cC]ompile|[cC]ompileOnly|[iI]mplementation|[aA]pi)$/)
            }.collect {
                Configuration copyConfiguration = it.copyRecursive()
                if (copyConfiguration.metaClass.respondsTo(copyConfiguration, "setCanBeResolved", Boolean)) {
                    copyConfiguration.setCanBeResolved(true)
                }

                copyConfiguration.resolvedConfiguration.resolvedArtifacts
            }.flatten() as List<ResolvedArtifact>
        }

        runtimeDependencies = runtimeDependencies.flatten()
        runtimeDependencies.removeAll([null])

        def seen = new HashSet<String>()
        def dependenciesToHandle = new HashSet<ResolvedArtifact>()
        runtimeDependencies.each { ResolvedArtifact d ->
            String dependencyDesc = "$d.moduleVersion.id.group:$d.moduleVersion.id.name:$d.moduleVersion.id.version"
            if (!seen.contains(dependencyDesc)) {
                dependenciesToHandle.add(d)

                Project subproject = subprojects[dependencyDesc]?.first()
                if (subproject) {
                    dependenciesToHandle.addAll(resolveProjectDependencies(subproject))
                }
            }
        }
        return dependenciesToHandle
    }
}
