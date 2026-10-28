package io.github.gccammelli.pandy

/**
 * The model sites Pandy browses. MakerWorld hands over complete Bambu projects; Thingiverse and Printables mostly offer
 * plain STL (or 3MF) files, which Pandy wraps into a 3MF (see [stlTo3mf]) for Pandy Slicer.
 */
enum class ModelSite(val label: String, val home: String, private val hosts: List<String>, private val fileHosts: List<String> = hosts) {
    MakerWorld("MakerWorld", "https://makerworld.com/en", listOf("makerworld.com"), listOf("makerworld.com", "bblmw.com")),
    Thingiverse("Thingiverse", "https://www.thingiverse.com", listOf("thingiverse.com")),
    Printables("Printables", "https://www.printables.com", listOf("printables.com"));

    /** Whether [host] is the site's own (or a subdomain of it). */
    fun ownsPage(host: String?): Boolean = host?.lowercase()?.removeSuffix(".")?.let { h -> hosts.any { h == it || h.endsWith(".$it") } } == true

    /** Whether [url] is an https address of a host the site serves its files from (no credentials, backslashes or spaces). */
    fun ownsFile(url: String): Boolean {
        if (!url.startsWith("https://") || url.any { it == '\\' || it == '@' || it.isWhitespace() }) return false
        val host = url.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':').lowercase()
        return host.isNotEmpty() && fileHosts.any { host == it || host.endsWith(".$it") }
    }

    /** The other sites, for the long-press menu. */
    val others: List<ModelSite> get() = entries.filter { it != this }
}

/** Hosts a model site's page may load in Pandy: the sites, Bambu's sign-in, and the sign-in providers. */
internal val SITE_HOSTS = listOf("makerworld.com", "bambulab.com", "bblmw.com", "thingiverse.com", "printables.com", "prusa3d.com",
    "appleid.apple.com", "accounts.google.com", "facebook.com")

/** Files Pandy can slice: STL and 3MF. [name] may carry a query string. */
fun isPrintableModel(name: String) = name.substringBefore('?').let { '.' in it && it.substringAfterLast('.').lowercase() in setOf("stl", "3mf") }
