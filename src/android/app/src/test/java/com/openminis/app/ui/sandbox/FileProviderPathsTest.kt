package com.openminis.app.ui.sandbox

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class FileProviderPathsTest {

    @Test
    fun verifyFileProviderPathsDeclarations() {
        val candidates = listOf(
            File("src/main/res/xml/file_provider_paths.xml"),
            File("app/src/main/res/xml/file_provider_paths.xml"),
            File("src/android/app/src/main/res/xml/file_provider_paths.xml"),
        )
        val xmlFile = candidates.firstOrNull { it.exists() }
            ?: File(System.getProperty("user.dir"), "src/main/res/xml/file_provider_paths.xml")
        assertTrue("file_provider_paths.xml should exist", xmlFile.exists())

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(xmlFile)
        val root = doc.documentElement
        assertTrue("Root element should be <paths>", root.tagName == "paths")

        val pathNodes = (0 until root.childNodes.length).map { root.childNodes.item(it) }
        val elements = pathNodes.filter { it.nodeType == org.w3c.dom.Node.ELEMENT_NODE }

        val tagsWithNames = elements.map {
            val tag = it.nodeName
            val name = it.attributes.getNamedItem("name")?.nodeValue.orEmpty()
            val path = it.attributes.getNamedItem("path")?.nodeValue.orEmpty()
            Triple(tag, name, path)
        }

        assertTrue(
            "Must declare guest-file-browser cache path",
            tagsWithNames.any { (tag, name, path) -> tag == "cache-path" && name == "guest-file-browser" && path.startsWith("guest-file-browser") }
        )
        assertTrue(
            "Must declare device_root",
            tagsWithNames.any { (tag, name, path) -> tag == "root-path" && name == "device_root" }
        )
        assertTrue(
            "Must declare app_cache fallback",
            tagsWithNames.any { (tag, name, path) -> tag == "cache-path" && name == "app_cache" && path == "." }
        )
        assertTrue(
            "Must declare app_files fallback",
            tagsWithNames.any { (tag, name, path) -> tag == "files-path" && name == "app_files" && path == "." }
        )
    }
}
