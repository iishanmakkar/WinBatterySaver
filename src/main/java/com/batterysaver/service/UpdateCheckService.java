package com.batterysaver.service;

import com.batterysaver.constants.AppConstants;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight update check - no auto-install, just a tray notice with link.
 * Disabled by default (0-cost: no outbound unless user opts in and sets repo slug).
 * GET https://api.github.com/repos/{slug}/releases/latest, compare tag to local version.
 */
public class UpdateCheckService {
    // Use AppConstants.VERSION (single source of truth) so the packaged version and
    // the update comparison never disagree.
    public static final String VERSION = AppConstants.VERSION;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    public record UpdateInfo(String latestTag, String htmlUrl, boolean newer) {}

    public UpdateInfo check(String repoSlug) {
        if (repoSlug == null || repoSlug.isBlank() || repoSlug.contains("REPLACE_ME")) {
            return null; // not configured
        }
        if (!repoSlug.matches("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")) {
            System.err.println("UpdateCheck: invalid slug " + repoSlug);
            return null;
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            String url = "https://api.github.com/repos/" + repoSlug + "/releases/latest";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "BatterySaver/" + VERSION)
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                System.err.println("UpdateCheck HTTP " + resp.statusCode());
                return null;
            }
            String body = resp.body();
            String tag = extractJsonString(body, "tag_name");
            String htmlUrl = extractJsonString(body, "html_url");
            if (tag == null) return null;
            // Normalize tag: v1.0.0 -> 1.0.0
            String latest = tag.startsWith("v") ? tag.substring(1) : tag;
            boolean newer = isNewer(VERSION, latest);
            return new UpdateInfo(tag, htmlUrl != null ? htmlUrl : ("https://github.com/" + repoSlug + "/releases/latest"), newer);
        } catch (Exception e) {
            // No internet / corporate block - silent per spec, keep optional
            System.err.println("UpdateCheck failed (offline or blocked): " + e.getMessage());
            return null;
        }
    }

    private static String extractJsonString(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** Simple semver compare: 1.0.1 > 1.0.0 */
    public static boolean isNewer(String current, String latest) {
        try {
            if (current != null && current.startsWith("v")) current = current.substring(1);
            if (latest != null && latest.startsWith("v")) latest = latest.substring(1);
            int[] cur = parseVer(current);
            int[] lat = parseVer(latest);
            for (int i = 0; i < Math.max(cur.length, lat.length); i++) {
                int cv = i < cur.length ? cur[i] : 0;
                int lv = i < lat.length ? lat[i] : 0;
                if (lv > cv) return true;
                if (lv < cv) return false;
            }
            return false;
        } catch (Exception e) { return false; }
    }

    private static int[] parseVer(String v) {
        String[] parts = v.split("[^0-9]+");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = Integer.parseInt(parts[i]); } catch (NumberFormatException ignored) { out[i] = 0; }
        }
        return out;
    }
}
