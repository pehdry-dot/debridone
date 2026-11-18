#!/usr/bin/env python3
"""A sample music addon in the Eclipse Music / BeatBoss manifest format, for trying DebridOne.

It serves audio files from a folder you name (by default DebridOne's own test recordings, which
were generated for the project) through the endpoints of the format:

    /manifest.json  /search  /stream/{id}  /album/{id}  /artist/{id}  /playlist/{id}
    /catalog/{id}   /lyrics

Everything lives under a path that starts with a token, the way addons sign their users in, so the
address to paste into Plugins > Music > Addons looks like

    http://127.0.0.1:8765/<token>/manifest.json

Only standard-library Python. It listens on this computer only. Use it with audio you are free to
use; it is a test tool, not a way to publish music.

    python3 scripts/debridone/sample-music-addon.py [--port 8765] [--token demo-token] [--audio DIR]
"""
import argparse
import json
import mimetypes
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote, unquote, urlparse

AUDIO = {".mp3": "mp3", ".flac": "flac", ".m4a": "m4a", ".ogg": "ogg", ".opus": "opus", ".wav": "wav", ".aiff": "aiff", ".aac": "aac"}


def scan(root):
    """Albums are the folders that hold audio; the folder above an album is its artist."""
    albums = []
    for folder, _dirs, files in sorted(os.walk(root)):
        tracks = sorted(f for f in files if os.path.splitext(f)[1].lower() in AUDIO)
        if not tracks:
            continue
        album = os.path.basename(folder) or "Songs"
        artist = os.path.basename(os.path.dirname(folder)) or "Various"
        albums.append({"id": "al%d" % (len(albums) + 1), "title": album, "artist": artist, "folder": folder, "files": tracks})
    songs = []
    for album in albums:
        for name in album["files"]:
            title = os.path.splitext(name)[0].lstrip("0123456789 -_.")
            songs.append({
                "id": "s%d" % (len(songs) + 1), "title": title or name, "artist": album["artist"], "album": album["title"], "albumId": album["id"],
                "path": os.path.join(album["folder"], name), "format": AUDIO[os.path.splitext(name)[1].lower()],
            })
    return albums, songs


class Addon(BaseHTTPRequestHandler):
    server_version = "SampleMusicAddon/1.0"

    def log_message(self, fmt, *args):  # the token is part of every path: keep it out of the terminal
        sys.stderr.write("%s %s\n" % (self.command, self.path.replace(self.server.token, "<token>")))

    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def track(self, song):
        return {"id": song["id"], "title": song["title"], "artist": song["artist"], "album": song["album"], "format": song["format"]}

    def do_GET(self):
        url = urlparse(self.path)
        prefix = "/" + self.server.token
        if not url.path.startswith(prefix + "/"):
            return self.reply(401, {"notice": {"level": "error", "title": "Unknown token", "message": "This address is not the one this addon was started with."}})
        path = unquote(url.path[len(prefix):])
        query = parse_qs(url.query)
        songs, albums = self.server.songs, self.server.albums
        base = "http://%s%s" % (self.headers.get("Host", "127.0.0.1"), prefix)

        if path == "/manifest.json":
            return self.reply(200, {
                "id": "org.debridone.sample-addon", "name": "Sample Addon", "version": "1.0.0",
                "description": "Serves the audio files of a folder on this computer, for trying DebridOne.",
                "contentType": "music", "types": ["track", "album", "artist"],
                "resources": ["search", "stream", "catalog", "settings", "lyrics"],
                "catalogs": [{"id": "all", "type": "track", "name": "All songs"}, {"id": "albums", "type": "album", "name": "Albums"}],
                "settings": [{"key": "order", "type": "select", "label": "Order of \"All songs\"", "default": "album",
                              "options": [{"value": "album", "label": "By album"}, {"value": "title", "label": "By title"}]}],
            })
        if path == "/search":
            text = (query.get("q") or [""])[0].lower()
            limit = int((query.get("limit") or ["50"])[0])
            hit = lambda *fields: any(text in field.lower() for field in fields)
            return self.reply(200, {
                "tracks": [self.track(s) for s in songs if hit(s["title"], s["artist"], s["album"])][:limit],
                "albums": [{"id": a["id"], "title": a["title"], "artist": a["artist"], "trackCount": len(a["files"])} for a in albums if hit(a["title"], a["artist"])][:limit],
                "artists": [{"id": "ar:" + name, "name": name} for name in sorted({a["artist"] for a in albums}) if hit(name)][:limit],
                "playlists": [],
            })
        if path.startswith("/stream/"):
            song = next((s for s in songs if s["id"] == path[len("/stream/"):]), None)
            if song is None:
                return self.reply(404, {"notice": {"level": "error", "title": "Not found", "message": "This addon has no such song."}})
            return self.reply(200, {"url": "%s/audio/%s" % (base, song["id"]), "format": song["format"], "quality": "original file", "manifest": "none", "encrypted": False})
        if path.startswith("/album/"):
            album = next((a for a in albums if a["id"] == path[len("/album/"):]), None)
            if album is None:
                return self.reply(404, {})
            return self.reply(200, {"id": album["id"], "title": album["title"], "artist": album["artist"],
                                    "tracks": [self.track(s) for s in songs if s["albumId"] == album["id"]]})
        if path.startswith("/artist/"):
            name = path[len("/artist/ar:"):]
            mine = [a for a in albums if a["artist"] == name]
            if not mine:
                return self.reply(404, {})
            return self.reply(200, {"id": "ar:" + name, "name": name, "topTracks": [self.track(s) for s in songs if s["artist"] == name][:20],
                                    "albums": [{"id": a["id"], "title": a["title"], "artist": a["artist"], "trackCount": len(a["files"])} for a in mine]})
        if path == "/catalog/all":
            ordered = sorted(songs, key=lambda s: s["title"].lower()) if (query.get("order") or ["album"])[0] == "title" else songs
            skip = int((query.get("skip") or ["0"])[0])
            return self.reply(200, {"items": [dict(self.track(s), type="track") for s in ordered[skip:skip + 100]]})
        if path == "/catalog/albums":
            return self.reply(200, {"items": [{"id": a["id"], "type": "album", "title": a["title"], "artist": a["artist"]} for a in albums]})
        if path == "/lyrics":
            return self.reply(404, {})
        if path.startswith("/audio/"):
            song = next((s for s in songs if s["id"] == path[len("/audio/"):]), None)
            if song is None:
                return self.reply(404, {})
            size = os.path.getsize(song["path"])
            start, end = 0, size - 1
            requested = self.headers.get("Range")
            if requested and requested.startswith("bytes="):
                first, _, last = requested[6:].partition("-")
                start = int(first or 0)
                end = min(int(last), size - 1) if last else size - 1
            self.send_response(206 if requested else 200)
            self.send_header("Content-Type", mimetypes.guess_type(song["path"])[0] or "application/octet-stream")
            self.send_header("Accept-Ranges", "bytes")
            if requested:
                self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
            self.send_header("Content-Length", str(end - start + 1))
            self.end_headers()
            with open(song["path"], "rb") as audio:
                audio.seek(start)
                try:
                    self.wfile.write(audio.read(end - start + 1))
                except (BrokenPipeError, ConnectionResetError):
                    pass
            return None
        return self.reply(404, {"error": "not found"})


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    default_audio = os.path.join(here, "..", "..", "debridone", "modules", "music", "src", "test", "resources", "audio-fixtures")
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--token", default="demo-token", help="the private part of the addon's address")
    parser.add_argument("--audio", default=default_audio, help="folder with audio files you are free to use")
    options = parser.parse_args()
    albums, songs = scan(os.path.abspath(options.audio))
    if not songs:
        sys.exit("No audio files found in %s" % options.audio)
    server = ThreadingHTTPServer(("127.0.0.1", options.port), Addon)
    server.token, server.albums, server.songs = quote(options.token, safe=""), albums, songs
    print("Sample addon with %d songs in %d albums." % (len(songs), len(albums)))
    print("Paste this into DebridOne > Plugins > Music > Addons:")
    print("  http://127.0.0.1:%d/%s/manifest.json" % (options.port, server.token))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
