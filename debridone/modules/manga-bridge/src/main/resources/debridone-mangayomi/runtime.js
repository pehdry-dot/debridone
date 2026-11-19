// The API a Mangayomi JavaScript source expects (MProvider, Client, Document, Element,
// SharedPreferences and the helper functions), on top of DebridOne's bridge. Its shape follows
// Mangayomi's own (https://github.com/kodjodevf/mangayomi, lib/eval/javascript, Apache License 2.0,
// Copyright the Mangayomi contributors); the work behind each call is done by the bridge in Java.
// Two calls are made the way Mangayomi meant them: Element.hasAttr sends the attribute and the
// element (Mangayomi sent the element's missing html), and Element.xpath/xpathFirst reach the
// element handlers.

function sendMessage(name, args) {
    return __host(name, args);
}

console.log = function (message) {
    if (typeof message === "object") message = JSON.stringify(message);
    __host("log", JSON.stringify([String(message)]));
};
console.warn = console.log;
console.error = console.log;
console.info = console.log;
console.debug = console.log;

String.prototype.substringAfter = function (pattern) {
    const startIndex = this.indexOf(pattern);
    if (startIndex === -1) return this.substring(0);
    return this.substring(startIndex + pattern.length);
};
String.prototype.substringAfterLast = function (pattern) {
    return this.split(pattern).pop();
};
String.prototype.substringBefore = function (pattern) {
    const endIndex = this.indexOf(pattern);
    if (endIndex === -1) return this.substring(0);
    return this.substring(0, endIndex);
};
String.prototype.substringBeforeLast = function (pattern) {
    const endIndex = this.lastIndexOf(pattern);
    if (endIndex === -1) return this.substring(0);
    return this.substring(0, endIndex);
};
String.prototype.substringBetween = function (left, right) {
    let index = this.indexOf(left, 0);
    if (index === -1) return "";
    let leftIndex = index + left.length;
    let rightIndex = this.indexOf(right, leftIndex);
    if (rightIndex === -1) return "";
    return this.substring(leftIndex, rightIndex);
};

class Client {
    constructor(reqcopyWith) {
        this.reqcopyWith = reqcopyWith;
    }
    async head(url, headers) {
        return JSON.parse(sendMessage("http_head", JSON.stringify([null, this.reqcopyWith, url, headers])));
    }
    async get(url, headers) {
        return JSON.parse(sendMessage("http_get", JSON.stringify([null, this.reqcopyWith, url, headers])));
    }
    async post(url, headers, body) {
        return JSON.parse(sendMessage("http_post", JSON.stringify([null, this.reqcopyWith, url, headers, body])));
    }
    async put(url, headers, body) {
        return JSON.parse(sendMessage("http_put", JSON.stringify([null, this.reqcopyWith, url, headers, body])));
    }
    async delete(url, headers, body) {
        return JSON.parse(sendMessage("http_delete", JSON.stringify([null, this.reqcopyWith, url, headers, body])));
    }
    async patch(url, headers, body) {
        return JSON.parse(sendMessage("http_patch", JSON.stringify([null, this.reqcopyWith, url, headers, body])));
    }
}

class Document {
    constructor(html) {
        this.html = html;
    }
    getElement(type) {
        return new Element(sendMessage("get_doc_element", JSON.stringify([this.html, type])));
    }
    get body() { return this.getElement("body"); }
    get documentElement() { return this.getElement("documentElement"); }
    get head() { return this.getElement("head"); }
    get parent() { return this.getElement("parent"); }
    getString(type) {
        return sendMessage("get_doc_string", JSON.stringify([this.html, type]));
    }
    get text() { return this.getString("text"); }
    get outerHtml() { return this.getString("outerHtml"); }
    selectFirst(selector) {
        return new Element(sendMessage("doc_select_first", JSON.stringify([this.html, selector])));
    }
    select(selector) {
        return JSON.parse(sendMessage("doc_select", JSON.stringify([this.html, selector]))).map((key) => new Element(key));
    }
    xpathFirst(xpath) {
        return sendMessage("doc_xpath_first", JSON.stringify([this.html, xpath]));
    }
    xpath(xpath) {
        return JSON.parse(sendMessage("doc_xpath", JSON.stringify([this.html, xpath])));
    }
    getElementsListBy(type, name) {
        return JSON.parse(sendMessage("doc_get_elements_by", JSON.stringify([this.html, type, name || ""]))).map((key) => new Element(key));
    }
    get children() { return this.getElementsListBy("children"); }
    getElementsByTagName(name) { return this.getElementsListBy("getElementsByTagName", name); }
    getElementsByClassName(name) { return this.getElementsListBy("getElementsByClassName", name); }
    getElementById(id) {
        return new Element(sendMessage("doc_get_element_by_id", JSON.stringify([this.html, id])));
    }
    attr(attr) {
        return sendMessage("doc_attr", JSON.stringify([this.html, attr]));
    }
    hasAttr(attr) {
        return sendMessage("doc_has_attr", JSON.stringify([this.html, attr]));
    }
}

class Element {
    constructor(key) {
        this.key = key;
    }
    getString(type) {
        return sendMessage("get_element_string", JSON.stringify([type, this.key]));
    }
    get text() { return this.getString("text"); }
    get outerHtml() { return this.getString("outerHtml"); }
    get innerHtml() { return this.getString("innerHtml"); }
    get className() { return this.getString("className"); }
    get localName() { return this.getString("localName"); }
    get namespaceUri() { return this.getString("namespaceUri"); }
    get getSrc() { return this.getString("getSrc"); }
    get getImg() { return this.getString("getImg"); }
    get getHref() { return this.getString("getHref"); }
    get getDataSrc() { return this.getString("getDataSrc"); }
    getElementSibling(type) {
        return new Element(sendMessage("ele_element_sibling", JSON.stringify([type, this.key])));
    }
    get previousElementSibling() { return this.getElementSibling("previousElementSibling"); }
    get nextElementSibling() { return this.getElementSibling("nextElementSibling"); }
    getElementsListBy(type, name) {
        return JSON.parse(sendMessage("ele_get_elements_by", JSON.stringify([type, name || "", this.key]))).map((key) => new Element(key));
    }
    get children() { return this.getElementsListBy("children"); }
    getElementsByTagName(name) { return this.getElementsListBy("getElementsByTagName", name); }
    getElementsByClassName(name) { return this.getElementsListBy("getElementsByClassName", name); }
    xpath(xpath) {
        return JSON.parse(sendMessage("ele_xpath", JSON.stringify([xpath, this.key])));
    }
    xpathFirst(xpath) {
        return sendMessage("ele_xpathFirst", JSON.stringify([xpath, this.key]));
    }
    attr(attr) {
        return sendMessage("ele_attr", JSON.stringify([attr, this.key]));
    }
    hasAttr(attr) {
        return sendMessage("ele_has_attr", JSON.stringify([attr, this.key]));
    }
    selectFirst(selector) {
        return new Element(sendMessage("ele_selectFirst", JSON.stringify([selector, this.key])));
    }
    select(selector) {
        return JSON.parse(sendMessage("ele_select", JSON.stringify([selector, this.key]))).map((key) => new Element(key));
    }
}

class SharedPreferences {
    get(key) {
        return JSON.parse(sendMessage("pref_get", JSON.stringify([key])));
    }
    getString(key, defaultValue) {
        return sendMessage("pref_getString", JSON.stringify([key, defaultValue]));
    }
    setString(key, value) {
        return sendMessage("pref_setString", JSON.stringify([key, value]));
    }
}

function cryptoHandler(text, iv, secretKeyString, encrypt) {
    return sendMessage("cryptoHandler", JSON.stringify([text, iv, secretKeyString, encrypt]));
}
function encryptAESCryptoJS(plainText, passphrase) {
    return sendMessage("encryptAESCryptoJS", JSON.stringify([plainText, passphrase]));
}
function decryptAESCryptoJS(encrypted, passphrase) {
    return sendMessage("decryptAESCryptoJS", JSON.stringify([encrypted, passphrase]));
}
function decryptAESGCM(encrypted, keyHex, ivHex, tagHex = "") {
    return sendMessage("decryptAESGCM", JSON.stringify([encrypted, keyHex, ivHex, tagHex]));
}
function deobfuscateJsPassword(inputString) {
    return sendMessage("deobfuscateJsPassword", JSON.stringify([inputString]));
}
function unpackJsAndCombine(scriptBlock) {
    return sendMessage("unpackJsAndCombine", JSON.stringify([scriptBlock]));
}
function unpackJs(packedJS) {
    return sendMessage("unpackJs", JSON.stringify([packedJS]));
}
function parseDates(value, dateFormat, dateFormatLocale) {
    return JSON.parse(sendMessage("parseDates", JSON.stringify([value, dateFormat, dateFormatLocale])));
}
async function evaluateJavascriptViaWebview(url, headers, scripts, time = 30) {
    return sendMessage("evaluateJavascriptViaWebview", JSON.stringify([url, headers, scripts, time]));
}
async function createHlsProxyUrl(url, headers = {}) {
    return sendMessage("unsupported", JSON.stringify(["createHlsProxyUrl"]));
}
async function parseEpub(bookName, url, headers) {
    return sendMessage("unsupported", JSON.stringify(["parseEpub"]));
}
async function parseEpubChapter(bookName, url, headers, chapterTitle) {
    return sendMessage("unsupported", JSON.stringify(["parseEpubChapter"]));
}

class MProvider {
    get source() {
        return __source;
    }
    get supportsLatest() {
        throw new Error("supportsLatest not implemented");
    }
    getHeaders(url) {
        throw new Error("getHeaders not implemented");
    }
    async getPopular(page) {
        throw new Error("getPopular not implemented");
    }
    async getLatestUpdates(page) {
        throw new Error("getLatestUpdates not implemented");
    }
    async search(query, page, filters) {
        throw new Error("search not implemented");
    }
    async getDetail(url) {
        throw new Error("getDetail not implemented");
    }
    async getPageList() {
        throw new Error("getPageList not implemented");
    }
    async getVideoList(url) {
        throw new Error("getVideoList not implemented");
    }
    async getHtmlContent(name, url) {
        throw new Error("getHtmlContent not implemented");
    }
    async cleanHtmlContent(html) {
        throw new Error("cleanHtmlContent not implemented");
    }
    getFilterList() {
        throw new Error("getFilterList not implemented");
    }
    getSourcePreferences() {
        throw new Error("getSourcePreferences not implemented");
    }
}

// Runs one call of the source and leaves its result (as JSON) or its error where the bridge reads it.
function __call(fn) {
    globalThis.__done = false;
    globalThis.__result = undefined;
    globalThis.__error = undefined;
    Promise.resolve()
        .then(() => fn())
        .then(
            (value) => { globalThis.__result = JSON.stringify(value === undefined ? null : value); globalThis.__done = true; },
            (error) => { globalThis.__error = String((error && error.stack) || error); globalThis.__done = true; },
        );
}
