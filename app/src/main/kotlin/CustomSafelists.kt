package land.melty.matrixappserviceqq

import org.jsoup.safety.Safelist

object CustomSafelists {
    /** @see https://spec.matrix.org/v1.2/client-server-api/#mroommessage-msgtypes */
    fun matrix() =
        Safelist()
            .addTags(
                "font", "del", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "p", "a",
                "ul", "ol", "sup", "sub", "li", "b", "i", "u", "strong", "em", "strike",
                "code", "hr", "br", "div", "table", "thead", "tbody", "tr", "th", "td",
                "caption", "pre", "span", "img", "details", "summary")
            .addAttributes("font", "data-mx-bg-color", "data-mx-color", "color")
            .addAttributes("span", "data-mx-bg-color", "data-mx-color", "data-mx-spoiler")
            .addAttributes("a", "name", "target", "href")
            .addAttributes("img", "width", "height", "alt", "title", "src")
            // MSC2545: Image Packs (Emoticons & Stickers)
            .addAttributes("img", "data-mx-emoticon")
            .addAttributes("ol", "start")
            .addAttributes("code", "class")
            .addProtocols("a", "href", "https", "http", "ftp", "mailto", "magnet")
            .addProtocols("img", "src", "mxc")
            .addEnforcedAttribute("a", "rel", "noopener")

    fun richReplies() =
        matrix()
            .addTags("mx-reply")

    fun qq() =
        Safelist()
            // Block, "\n...\n"
            .addTags("h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "p", "table", "pre")
            // Line, "...\n"
            .addTags("tr", "caption")
            // Atom, hr -> "-----", br -> "\n"
            .addTags("hr", "br")
            // List, ul -> "\n* ...\n* ...\n", ol -> "\n1. ...\n2. ...\n"
            .addTags("ul", "ol", "li")
            // Special, a -> " ...(...) " or At, img -> Image
            .addTags("a", "img")
            .addAttributes("a", "href")
            .addAttributes("img", "src", "data-mx-emoticon")
            .addAttributes("ol", "start")
            .addProtocols("a", "href", "https", "http", "ftp", "mailto", "magnet")
            .addProtocols("img", "src", "mxc")
}
