package com.confluence.docassistant.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Represents a Confluence page as returned by the REST API.
 * Only maps fields we actually use — everything else is ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConfluencePage {

    private String id;
    private String title;
    private Body body;
    private Links _links;
    private Version version;
    private List<ConfluencePage> ancestors;

    // ── Nested: Body ─────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Body {
        private Storage storage;
        public Storage getStorage() { return storage; }
        public void setStorage(Storage storage) { this.storage = storage; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Storage {
        private String value;
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    // ── Nested: Links ────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Links {
        private String webui;
        public String getWebui() { return webui; }
        public void setWebui(String webui) { this.webui = webui; }
    }

    // ── Nested: Version ──────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Version {
        private int number;
        private String when;
        public int getNumber() { return number; }
        public void setNumber(int number) { this.number = number; }
        public String getWhen() { return when; }
        public void setWhen(String when) { this.when = when; }
    }

    // ── Nested: PageList (API response wrapper) ───────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PageList {
        private List<ConfluencePage> results;
        private int size;
        public List<ConfluencePage> getResults() { return results; }
        public void setResults(List<ConfluencePage> results) { this.results = results; }
        public int getSize() { return size; }
        public void setSize(int size) { this.size = size; }
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Body getBody() { return body; }
    public void setBody(Body body) { this.body = body; }
    public Links get_links() { return _links; }
    public void set_links(Links _links) { this._links = _links; }
    public Version getVersion() { return version; }
    public void setVersion(Version version) { this.version = version; }
    public List<ConfluencePage> getAncestors() { return ancestors; }
    public void setAncestors(List<ConfluencePage> ancestors) { this.ancestors = ancestors; }
}
