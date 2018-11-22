package io.github.cdimascio.unfluff

import com.sun.tools.javac.tree.JCTree
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.parser.Tag

val REGEX_BAD_TAGS = """^side$|combx|retweet|mediaarticlerelated|menucontainer|navbar|partner-gravity-ad|video-full-transcript|storytopbar-bucket|utility-bar|inline-share-tools|comment|PopularQuestions|contact|foot|footer|Footer|footnote|cnn_strycaptiontxt|cnn_html_slideshow|cnn_strylftcntnt|links|meta$|shoutbox|sponsor|tags|socialnetworking|socialNetworking|cnnStryHghLght|cnn_stryspcvbx|^inset$|pagetools|post-attributes|welcome_form|contentTools2|the_answers|communitypromo|runaroundLeft|subscribe|vcard|articleheadings|date|^print$|popup|author-dropdown|tools|socialtools|byline|konafilter|KonaFilter|breadcrumbs|^fn$|wp-caption-text|legende|ajoutVideo|timestamp|js_replies""".toRegex(RegexOption.IGNORE_CASE)

class Cleaner(private val doc: Document, private val language: Language) {
    private var d = doc
    fun clean(): CleanDocument {
        removeBodyClasses()
        cleanArticleTags()
        cleanEmTags()
        cleanCodeBlocks()
        removeDropCaps()
        removeScriptsStyles()
        Traverse(
            nodeRemovalRules = listOf(
                TraversalRules::removeCommentsTravRule,
                TraversalRules::removeBadTagsTravRule,
                (TraversalRules::removeMatching)("""^caption$""".toRegex()),
                (TraversalRules::removeMatching)(""" google """.toRegex()),
                (TraversalRules::removeMatching)("""^[^entry-]more.*$""".toRegex()),
                (TraversalRules::removeMatching)("""[^-]facebook""".toRegex()),
                (TraversalRules::removeMatching)("""facebook-broadcasting""".toRegex()),
                (TraversalRules::removeMatching)("""[^-]twitter""".toRegex())),
            nodeModificationRules = listOf(
                TraversalRules::correctErrantLineBreaks
//                (TraversalRules::tagsToParagraph)(listOf("div", "span", "a"))
            ))
            .applyRules(d)
            .purgeMarkedNodes()
        cleanParaSpans()
        cleanUnderlines()

        elementToParagraph(d, "div")
        elementToParagraph(d, "span")

        return CleanDocument(
            text = d.html(),
            language = language,
            doc = d
        )
    }

    /**
     * Remove all classes
     */
    private fun removeBodyClasses() {
        val body = d.body()
        body.classNames().forEach {
            body.removeClass(it)
        }
    }

    private fun cleanArticleTags() {
        val articles = d.getElementsByTag("article")
        articles.forEach {
            it.removeAttr("id")
            it.removeAttr("name")
            it.removeAttr("class")
        }
    }

    private fun cleanEmTags() {
        val ems = d.getElementsByTag("em")
        ems.forEach {
            val images = ems.select("img")
            if (images.isEmpty()) {

                it.replaceWith(htmlToElement(it.html()))
            }
        }
    }

    private fun cleanCodeBlocks() {
        val nodes = d.select(
            "[class*='highlight-'], pre code, code, pre, ul.task-list"
        )
        nodes.forEach {
            it.replaceWith(htmlToElement(it.html()))
        }
    }

    private fun removeDropCaps() {
        val nodes = d.select("span[class~=dropcap], span[class~=drop_cap]")
        return nodes.forEach {
            it.replaceWith(htmlToElement(it.html()))
        }
    }

    private fun removeScriptsStyles() {
        d.getElementsByTag("script").remove()
        d.getElementsByTag("style").remove()
    }

    private fun cleanParaSpans() {
        d.select("p span").forEach {
            it.replaceWith(htmlToElement(it.html()))
        }
    }

    private fun cleanUnderlines() {
        d.select("u").forEach {
            it.replaceWith(htmlToElement(it.html()))
        }
    }

    private fun htmlToElement2(parent: Element, html: String): Node {
        // xml parser may break certain features
        // htmlParser adds the html body tags
        if (!html.trim().startsWith("<")) {
            return TextNode(html)
        }
        val element = Jsoup.parse(html, "", Parser.xmlParser())
        element.appendTo(parent)
        return element
    }

    private fun htmlToElement(html: String): Node {
        // xml parser may break certain features
        // htmlParser adds the html body tags
        val cleanHtml = html.trim()
        // TODO will this fail if a code block or something started with a '<'
        if (!cleanHtml.startsWith("<")) {
            return TextNode(html)
        }
        val (tag) = """^<(.*)>""".toRegex().find(cleanHtml)?.destructured ?: throw IllegalArgumentException("bad html. element detected, but not element")
        val element = Jsoup.parse(cleanHtml, "", Parser.xmlParser())
        element.tagName(tag)
        return element
    }

    private fun elementToParagraph(doc: Document, tagName: String) {
        val elements = doc.select(tagName)
        val tags = listOf("a", "blockquote", "dl", "div", "img", "ol", "p", "pre", "table", "ul")
        for (element in elements) {
            val items = element.select(tags.joinToString(", "))
            if (items.isEmpty()) {
                element.replaceWith(htmlToElement("<p>${element.html()}</p>"))
            } else {
                val replacementNodes = ReplacementNodes().find(element)
                val html = replacementNodes.fold("") { html, nodeHtml ->
                    if (nodeHtml.isNotBlank()) {
                        "$html <p>$nodeHtml</p>"
                    } else html
                }
                element.replaceWith(htmlToElement(html))
            }
        }
    }
}

class Traverse(val nodeRemovalRules: List<(Node) -> Boolean>, val nodeModificationRules: List<(Node) -> Unit>) {
    private val nodesToRemove = mutableSetOf<Node>()
    fun applyRules(node: Node): Traverse {
        for (child in node.childNodes()) {
            var nodeMarkedForRemoval = false
            for (rule in nodeRemovalRules) {
                if (rule(child)) {
                    nodeMarkedForRemoval = true
                    nodesToRemove += child
                    break
                }
            }
            if (nodeMarkedForRemoval) continue

            for (rule in nodeModificationRules) {
                rule(child)
            }
            applyRules(child)
        }
        return this
    }

    /**
     * purge nodes marked for deletion
     */
    fun purgeMarkedNodes() {
        nodesToRemove.forEach {
            println("removing ${it.nodeName()}")
            it.remove()
        }
    }

}

object TraversalRules {
    /**
     * Remove node if a comment nodes and return true, else return false
     */
    fun removeCommentsTravRule(node: Node): Boolean {
        if (node.nodeName() == "#comment") println("comment rules")
        return node.nodeName() == "#comment"
    }

    fun removeBadTagsTravRule(node: Node) =
        node.attr("id").matches(REGEX_BAD_TAGS) ||
            node.attr("class").matches(REGEX_BAD_TAGS) ||
            node.attr("name").matches(REGEX_BAD_TAGS)

    fun removeMatching(re: Regex): (Node) -> Boolean {
        return { node: Node ->
            node.attr("id").matches(re) ||
                node.attr("class").matches(re)
        }
    }

    fun correctErrantLineBreaks(node: Node) {
        if (node is Element && node.tagName() == "p") {
            for (textNode in node.textNodes()) {
                val text = textNode.text().replace("""([^\n])\n([^\n])""".toRegex()) {
                    it.groupValues.joinToString(" ")
                }
                textNode.text(text)
            }
        }
    }

    fun tagsToParagraph(tags: List<String>): (Node) -> Unit {
        return { node ->
            val itemTags = listOf("a", "blockquote", "dl", "div", "img", "ol", "p", "pre", "table", "ul")
            if (node is Element && tags.contains(node.tagName())) {
                if (node.text().isNotBlank()) {
                    node.tagName("p")
                }
            }
        }
    }
}

class ReplacementNodes {
    private val replacementText = mutableListOf<String>()
    //    val nodesToReturn = mutableListOf<Node>()
    private val nodesToReturn = mutableListOf<String>()
    private val nodesToRemove = mutableListOf<Node>()

    fun find(div: Element): List<String> {
        for (child in div.childNodes()) {
            when (child) {
                is Element -> {
                    if (child.tagName() == "p" && replacementText.isNotEmpty()) {
                        val text = replacementText.joinToString("")
                        if (text.isNotBlank()) {
                            nodesToReturn.add(text)
                        }
                        replacementText.clear()
                        val html = child.html()
                        nodesToReturn.add(html)
//                        if (html.isNotBlank()) {
//                            nodesToReturn.addAll(child.childNodes())
//                        }
                    } else {
                        val html = child.html()
                        nodesToReturn.add(html)
//                        if (html.isNotBlank()) {
//                            nodesToReturn.addAll(child.childNodes())
//                        }
                    }

                }
                is TextNode -> {
                    val text = child.text().replace("""\n""".toRegex(), """\n\n""").replace("""\t""".toRegex(), "").replace("""^\s+$""".toRegex(), "")
                    val GRAVITY_USED_ALREADY = "grv-used-already"
                    val isGravityusedAlready = { element: Element -> element.attr(GRAVITY_USED_ALREADY) == "yes" }
                    val setGravityusedAlready = { element: Element -> element.attr(GRAVITY_USED_ALREADY, "yes") }
                    if (text.length > 1) {
                        val previousSibling: Node? = child.previousSibling()
                        if (previousSibling is Element) {
                            var sib: Element? = previousSibling
                            var usedAlready = sib?.let { isGravityusedAlready(it) } ?: true
                            while (sib != null && sib.tagName() == "a" && !usedAlready) {

                                val outer = " ${sib.outerHtml()} "
                                replacementText.add(outer)
                                nodesToRemove.add(sib)
                                setGravityusedAlready(sib)
                                sib = sib.previousElementSibling()
                                usedAlready = sib?.let { isGravityusedAlready(it) } ?: true
                            }
                        }
                        replacementText.add(text)

                        val nextSibling = child.nextSibling()

                        if (nextSibling is Element) {
                            var sib: Element? = nextSibling
                            var usedAlready = sib?.let { isGravityusedAlready(it) } ?: true
                            while (sib != null && sib.tagName() == "a" && !usedAlready) {
                                val outer = " ${sib.outerHtml()} "
                                replacementText.add(outer)
                                nodesToRemove.add(sib)
                                setGravityusedAlready(sib)
                                sib = sib.nextElementSibling()
                                usedAlready = sib?.let { isGravityusedAlready(it) } ?: true
                            }
                        }
                    }
                }
                else -> {
                }
            } // when
        } // for

        if (replacementText.isNotEmpty()) {
            val text = replacementText.joinToString("")
            if (text.isNotBlank()) {
                nodesToReturn.add(text)
            }
        }

        nodesToRemove.filter { it.parentNode() != null }.forEach {
            it.remove()
        }
        return nodesToReturn
    }
}
