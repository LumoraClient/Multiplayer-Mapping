import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Builds the Lumora multiplayer mapping from two public sources:
 * <ul>
 *     <li><a href="https://github.com/NoRiskClient/nrc-server-mappings">NoRiskClient/nrc-server-mappings</a>
 *     (GPL-3.0): pretty name, addresses, categories, game modes, icon and background;</li>
 *     <li><a href="https://github.com/LabyMod/server-media">LabyMod/server-media</a>: only the facts nice name,
 *     direct address, wildcard domains and game modes (name, colour, versions); no pictures, no other fields.</li>
 * </ul>
 * Servers of both sources are joined by their addresses. The result is {@code servers.json} plus the NoRisk pictures,
 * scaled to what the client shows (background 640x360, icon 128x128), in {@code servers/<id>/}. The LabyMod manifests are
 * also copied unchanged to {@code labymod/<folder>/manifest.json}.
 * <p>
 * Usage: {@code java tools/BuildMapping.java <repository folder>}. Needs Java 21. Every download is checked against
 * its git blob hash from the source tree. An optional {@code GITHUB_TOKEN} environment variable is sent only to
 * {@code api.github.com} (higher rate limit in GitHub Actions) and never printed. Nothing is written if a source
 * cannot be read or looks broken.
 */
public class BuildMapping {
    static final String NRC = "NoRiskClient/nrc-server-mappings";
    static final String NRC_BRANCH = "main";
    static final String LABY = "LabyMod/server-media";
    static final String LABY_BRANCH = "master";
    static final int BACKGROUND_W = 640;
    static final int BACKGROUND_H = 360;
    static final int ICON = 128;
    static final Pattern DIR = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,63}");
    static final Pattern PNG_FILE = Pattern.compile("[A-Za-z0-9_-]{1,64}\\.png");
    static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
    static final Pattern HOST = Pattern.compile("[a-z0-9]([a-z0-9-]{0,62})?(\\.[a-z0-9]([a-z0-9-]{0,62})?)+");
    static final Pattern IPV6 = Pattern.compile("\\[[0-9a-f:.]{2,45}]");
    static final Pattern CATEGORY = Pattern.compile("[A-Z0-9_]{1,32}");
    static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    static final Pattern VERSIONS = Pattern.compile("[0-9.*<, ]{1,200}");

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    record Mode(String name, String color, String versions) {
    }

    record Nrc(String dir, String name, List<String> addresses, List<String> categories, List<Mode> modes,
               String icon, String iconSha, String background, String backgroundSha) {
    }

    record Laby(String dir, String name, List<String> addresses, List<Mode> modes) {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path serversJson = root.resolve("servers.json");
        Map<String, Object> old = Files.isRegularFile(serversJson)
                ? obj(Json.parse(Files.readString(serversJson, StandardCharsets.UTF_8))) : Map.of();
        Map<String, Object> oldServers = obj(old.get("servers"));

        List<Nrc> nrc = readNrc();
        List<Laby> laby = readLaby();
        int oldCount = oldServers.size();
        if (nrc.size() < 20 || laby.size() < 50) {
            throw new IllegalStateException("a source looks broken: " + nrc.size() + " NoRisk, " + laby.size() + " LabyMod");
        }

        // join: every LabyMod server takes the NoRisk server it shares an address with (exact match first)
        record Pair(int score, Laby l, Nrc n) {
        }
        List<Pair> pairs = new ArrayList<>();
        for (Laby l : laby) {
            for (Nrc n : nrc) {
                int s = related(l.addresses(), n.addresses());
                if (s > 0) {
                    pairs.add(new Pair(s, l, n));
                }
            }
        }
        pairs.sort(Comparator.comparingInt(Pair::score).reversed()
                .thenComparing(p -> p.n().dir()).thenComparing(p -> p.l().dir()));
        Map<String, Laby> joined = new HashMap<>();
        Set<String> usedLaby = new HashSet<>();
        for (Pair p : pairs) {
            if (!joined.containsKey(p.n().dir()) && usedLaby.add(p.l().dir())) {
                joined.put(p.n().dir(), p.l());
            }
        }

        Map<String, Map<String, Object>> out = new TreeMap<>();
        for (Nrc n : nrc) {
            Laby l = joined.get(n.dir());
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", n.name());
            e.put("addresses", union(n.addresses(), l == null ? List.of() : l.addresses()));
            e.put("categories", n.categories());
            List<Mode> modes = l != null && !l.modes().isEmpty() ? l.modes() : n.modes();
            e.put("gamemodes", modes(modes));
            Map<String, Object> from = new LinkedHashMap<>();
            from.put("norisk", n.dir());
            if (l != null) {
                from.put("labymod", l.dir());
            }
            e.put("from", from);
            out.put(n.dir(), e);
        }
        for (Laby l : laby) {
            if (usedLaby.contains(l.dir())) {
                continue;
            }
            String id = out.containsKey(l.dir()) ? l.dir() + "-labymod" : l.dir();
            if (!DIR.matcher(id).matches() || out.containsKey(id)) {
                continue;
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", l.name());
            e.put("addresses", l.addresses());
            e.put("categories", List.of());
            e.put("gamemodes", modes(l.modes()));
            e.put("from", Map.of("labymod", l.dir()));
            out.put(id, e);
        }
        if (oldCount > 0 && out.size() < oldCount / 2) {
            throw new IllegalStateException("only " + out.size() + " servers instead of " + oldCount + ": not written");
        }

        // pictures (NoRisk only), scaled; reused while their source is unchanged
        Path dir = root.resolve("servers");
        Files.createDirectories(dir);
        int downloaded = 0;
        for (Nrc n : nrc) {
            Map<String, Object> e = out.get(n.dir());
            Map<String, Object> oldEntry = obj(oldServers.get(n.dir()));
            for (boolean bg : new boolean[]{true, false}) {
                String file = bg ? n.background() : n.icon();
                String sourceSha = bg ? n.backgroundSha() : n.iconSha();
                String key = bg ? "background" : "icon";
                String target = key + ".png";
                if (file == null || sourceSha == null) {
                    continue;
                }
                Path path = dir.resolve(n.dir()).resolve(target);
                Map<String, Object> was = obj(oldEntry.get(key));
                if (sourceSha.equals(was.get("source")) && Files.isRegularFile(path)
                        && blobSha(Files.readAllBytes(path)).equals(was.get("sha"))) {
                    e.put(key, was);
                    continue;
                }
                byte[] png = get("https://raw.githubusercontent.com/" + NRC + "/" + NRC_BRANCH + "/servers/" + n.dir() + "/" + file,
                        16 * 1024 * 1024);
                downloaded++;
                if (!sourceSha.equals(blobSha(png))) {
                    System.out.println("skipped " + n.dir() + "/" + file + ": hash differs from the tree");
                    continue;
                }
                byte[] scaled = scale(png, bg ? BACKGROUND_W : ICON, bg ? BACKGROUND_H : ICON);
                if (scaled == null) {
                    System.out.println("skipped " + n.dir() + "/" + file + ": not a readable image");
                    continue;
                }
                Files.createDirectories(path.getParent());
                Files.write(path, scaled);
                Map<String, Object> pic = new LinkedHashMap<>();
                pic.put("file", "servers/" + n.dir() + "/" + target);
                pic.put("sha", blobSha(scaled));
                pic.put("source", sourceSha);
                e.put(key, pic);
            }
        }

        int labyWritten = copyLabyFiles(root);

        // remove pictures and folders that are no longer part of the mapping
        try (Stream<Path> folders = Files.list(dir)) {
            for (Path f : folders.toList()) {
                String name = f.getFileName().toString();
                Map<String, Object> e = out.get(name);
                if (!Files.isDirectory(f) || !DIR.matcher(name).matches()) {
                    continue;
                }
                try (Stream<Path> files = Files.list(f)) {
                    for (Path p : files.toList()) {
                        String key = p.getFileName().toString().replace(".png", "");
                        if (e == null || !e.containsKey(key)) {
                            Files.delete(p);
                        }
                    }
                }
                try (Stream<Path> rest = Files.list(f)) {
                    if (rest.findAny().isEmpty()) {
                        Files.delete(f);
                    }
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", 1);
        Map<String, Object> sources = new LinkedHashMap<>();
        sources.put("norisk", "https://github.com/" + NRC + " (GPL-3.0)");
        sources.put("labymod", "https://github.com/" + LABY + " (facts only: names, addresses, game modes)");
        result.put("sources", sources);
        Map<String, Object> servers = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : out.entrySet()) {
            Map<String, Object> ordered = new LinkedHashMap<>();
            for (String k : List.of("name", "addresses", "categories", "icon", "background", "gamemodes", "from")) {
                if (e.getValue().containsKey(k)) {
                    ordered.put(k, e.getValue().get(k));
                }
            }
            servers.put(e.getKey(), ordered);
        }
        result.put("servers", servers);
        String text = Json.write(result) + "\n";
        String before = Files.isRegularFile(serversJson) ? Files.readString(serversJson, StandardCharsets.UTF_8) : "";
        if (!text.equals(before)) {
            Files.writeString(serversJson, text, StandardCharsets.UTF_8);
        }
        System.out.println("NoRisk " + nrc.size() + ", LabyMod " + laby.size() + ", joined " + joined.size()
                + ", servers " + servers.size() + ", pictures downloaded " + downloaded + ", LabyMod manifests written " + labyWritten
                + (text.equals(before) ? ", servers.json unchanged" : ", servers.json written"));
    }

    // --- sources

    static List<Nrc> readNrc() throws Exception {
        Map<String, Map<String, String>> folders = new TreeMap<>();
        for (Object o : list(obj(Json.parse(api("https://api.github.com/repos/" + NRC + "/git/trees/" + NRC_BRANCH + "?recursive=1"))).get("tree"))) {
            Map<String, Object> b = obj(o);
            String path = str(b.get("path"));
            String sha = str(b.get("sha"));
            String[] parts = path.split("/");
            if ("blob".equals(b.get("type")) && parts.length == 3 && parts[0].equals("servers")
                    && DIR.matcher(parts[1]).matches() && SHA.matcher(sha).matches()
                    && (parts[2].equals("manifest.json") || PNG_FILE.matcher(parts[2]).matches())) {
                folders.computeIfAbsent(parts[1], k -> new TreeMap<>()).put(parts[2], sha);
            }
        }
        List<Future<Nrc>> jobs = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (Map.Entry<String, Map<String, String>> f : folders.entrySet()) {
                String manifestSha = f.getValue().get("manifest.json");
                if (manifestSha == null) {
                    continue;
                }
                jobs.add(pool.submit(() -> {
                    byte[] data = get("https://raw.githubusercontent.com/" + NRC + "/" + NRC_BRANCH + "/servers/" + f.getKey() + "/manifest.json", 256 * 1024);
                    if (!manifestSha.equals(blobSha(data))) {
                        throw new IOException("NoRisk " + f.getKey() + ": manifest hash differs from the tree");
                    }
                    try {
                        return nrc(f.getKey(), obj(Json.parse(new String(data, StandardCharsets.UTF_8))), f.getValue());
                    } catch (RuntimeException e) {
                        System.out.println("skipped NoRisk " + f.getKey() + ": " + e.getMessage());
                        return null;
                    }
                }));
            }
        }
        List<Nrc> out = new ArrayList<>();
        for (Future<Nrc> j : jobs) {
            Nrc n = j.get();
            if (n != null) {
                out.add(n);
            }
        }
        return out;
    }

    static Nrc nrc(String dir, Map<String, Object> o, Map<String, String> files) {
        String name = clip(str(o.get("pretty-name")), 48);
        List<String> addresses = hosts(o.get("server-address"));
        if (name.isEmpty() || addresses.isEmpty()) {
            return null;
        }
        List<String> categories = new ArrayList<>();
        for (Object c : list(o.get("categorys"))) {
            String s = str(c).strip().toUpperCase(Locale.ROOT);
            if (CATEGORY.matcher(s).matches() && !categories.contains(s) && categories.size() < 16) {
                categories.add(s);
            }
        }
        List<Mode> modes = new ArrayList<>();
        for (Object g : obj(o.get("gamemodes")).values()) {
            Map<String, Object> m = obj(g);
            String n = clip(str(m.get("name")), 40);
            String v = str(m.get("versions")).strip();
            if (!n.isEmpty() && modes.size() < 64) {
                modes.add(new Mode(n, null, VERSIONS.matcher(v).matches() ? v : ""));
            }
        }
        Map<String, Object> assets = obj(o.get("assets"));
        String icon = asset(str(assets.get("icon")), files);
        String background = asset(str(assets.get("background")), files);
        return new Nrc(dir, name, addresses, categories, modes, icon, icon == null ? null : files.get(icon),
                background, background == null ? null : files.get(background));
    }

    static String asset(String ref, Map<String, String> files) {
        String f = ref.startsWith("./") ? ref.substring(2) : ref;
        return PNG_FILE.matcher(f).matches() && files.containsKey(f) ? f : null;
    }

    /** The verified LabyMod manifests, byte for byte as in the source, by folder. */
    static final Map<String, byte[]> LABY_FILES = new java.util.concurrent.ConcurrentHashMap<>();

    /** Copies the LabyMod manifests unchanged to {@code labymod/<folder>/manifest.json}; removes folders gone upstream. */
    static int copyLabyFiles(Path root) throws IOException {
        Path dir = root.resolve("labymod");
        Files.createDirectories(dir);
        int written = 0;
        for (Map.Entry<String, byte[]> e : new TreeMap<>(LABY_FILES).entrySet()) {
            Path file = dir.resolve(e.getKey()).resolve("manifest.json");
            if (Files.isRegularFile(file) && java.util.Arrays.equals(Files.readAllBytes(file), e.getValue())) {
                continue;
            }
            Files.createDirectories(file.getParent());
            Files.write(file, e.getValue());
            written++;
        }
        try (Stream<Path> folders = Files.list(dir)) {
            for (Path f : folders.toList()) {
                if (Files.isDirectory(f) && !LABY_FILES.containsKey(f.getFileName().toString())) {
                    try (Stream<Path> walk = Files.walk(f)) {
                        for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                            Files.delete(p);
                        }
                    }
                }
            }
        }
        return written;
    }

    static List<Laby> readLaby() throws Exception {
        Map<String, String> folders = new TreeMap<>();
        for (Object o : list(obj(Json.parse(api("https://api.github.com/repos/" + LABY + "/git/trees/" + LABY_BRANCH + "?recursive=1"))).get("tree"))) {
            Map<String, Object> b = obj(o);
            String[] parts = str(b.get("path")).split("/");
            String sha = str(b.get("sha"));
            if ("blob".equals(b.get("type")) && parts.length == 3 && parts[0].equals("minecraft_servers")
                    && parts[2].equals("manifest.json") && DIR.matcher(parts[1]).matches() && SHA.matcher(sha).matches()) {
                folders.put(parts[1], sha);
            }
        }
        List<Future<Laby>> jobs = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (Map.Entry<String, String> f : folders.entrySet()) {
                jobs.add(pool.submit(() -> {
                    byte[] data = get("https://raw.githubusercontent.com/" + LABY + "/" + LABY_BRANCH + "/minecraft_servers/" + f.getKey() + "/manifest.json", 256 * 1024);
                    if (!f.getValue().equals(blobSha(data))) {
                        throw new IOException("LabyMod " + f.getKey() + ": manifest hash differs from the tree");
                    }
                    LABY_FILES.put(f.getKey(), data);
                    try {
                        return laby(f.getKey(), obj(Json.parse(new String(data, StandardCharsets.UTF_8))));
                    } catch (RuntimeException e) {
                        System.out.println("skipped LabyMod " + f.getKey() + ": " + e.getMessage());
                        return null;
                    }
                }));
            }
        }
        List<Laby> out = new ArrayList<>();
        for (Future<Laby> j : jobs) {
            Laby l = j.get();
            if (l != null) {
                out.add(l);
            }
        }
        return out;
    }

    /** Only nice name, direct address, wildcard domains and game modes are taken over. */
    static Laby laby(String dir, Map<String, Object> o) {
        String name = clip(str(o.get("nice_name")), 48);
        List<Object> raw = new ArrayList<>();
        raw.add(o.get("direct_ip"));
        for (Object w : list(o.get("server_wildcards"))) {
            String s = str(w).strip();
            if (s.startsWith("%.") && !s.substring(2).contains("%")) {
                raw.add(s.substring(2));
            }
        }
        List<String> addresses = hosts(raw);
        if (name.isEmpty() || addresses.isEmpty()) {
            return null;
        }
        List<Mode> modes = new ArrayList<>();
        for (Object g : obj(o.get("gamemodes")).values()) {
            Map<String, Object> m = obj(g);
            String n = clip(str(m.get("name")), 40);
            String c = str(m.get("color"));
            String v = str(m.get("versions")).strip();
            if (!n.isEmpty() && modes.size() < 64) {
                modes.add(new Mode(n, COLOR.matcher(c).matches() ? c.toUpperCase(Locale.ROOT) : null,
                        VERSIONS.matcher(v).matches() ? v : ""));
            }
        }
        return new Laby(dir, name, addresses, modes);
    }

    // --- joining

    /** 2 if both share an address, 1 if one is a subdomain of the other's, 0 if unrelated. */
    static int related(List<String> a, List<String> b) {
        int best = 0;
        for (String x : a) {
            for (String y : b) {
                if (x.equals(y)) {
                    return 2;
                }
                if (x.endsWith("." + y) || y.endsWith("." + x)) {
                    best = 1;
                }
            }
        }
        return best;
    }

    static List<String> union(List<String> a, List<String> b) {
        Set<String> s = new LinkedHashSet<>(a);
        s.addAll(b);
        return new ArrayList<>(s).subList(0, Math.min(s.size(), 32));
    }

    /** Lower-case host names without port; anything that is not a plain host, IPv4 or bracketed IPv6 is dropped. */
    static List<String> hosts(Object value) {
        List<Object> in = value instanceof List<?> l ? new ArrayList<>(l) : value == null ? List.of() : List.of(value);
        Set<String> out = new LinkedHashSet<>();
        for (Object o : in) {
            String s = str(o).strip().toLowerCase(Locale.ROOT);
            if (!s.startsWith("[")) {
                int colon = s.indexOf(':');
                if (colon >= 0 && s.indexOf(':', colon + 1) < 0) {
                    s = s.substring(0, colon);
                }
            } else if (s.contains("]:")) {
                s = s.substring(0, s.indexOf("]:") + 1);
            }
            while (s.endsWith(".")) {
                s = s.substring(0, s.length() - 1);
            }
            if (s.length() <= 253 && (HOST.matcher(s).matches() || IPV6.matcher(s).matches()) && out.size() < 32) {
                out.add(s);
            }
        }
        return new ArrayList<>(out);
    }

    static List<Object> modes(List<Mode> modes) {
        List<Object> out = new ArrayList<>();
        for (Mode m : modes) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("name", m.name());
            if (m.color() != null) {
                o.put("color", m.color());
            }
            if (!m.versions().isEmpty()) {
                o.put("versions", m.versions());
            }
            out.add(o);
        }
        return out;
    }

    // --- pictures

    /** The PNG scaled to {@code w x h} (halving steps, then bilinear), as PNG; null if unreadable or huge. */
    static byte[] scale(byte[] png, int w, int h) throws IOException {
        BufferedImage src;
        try (InputStream in = new ByteArrayInputStream(png)) {
            src = ImageIO.read(in);
        }
        if (src == null || src.getWidth() > 8192 || src.getHeight() > 8192) {
            return null;
        }
        BufferedImage cur = src;
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) {
            cur = draw(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        }
        BufferedImage dst = draw(cur, w, h);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(dst, "png", out);
        return out.toByteArray();
    }

    static BufferedImage draw(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    // --- network

    static String api(String url) throws IOException, InterruptedException {
        return new String(get(url, 16 * 1024 * 1024), StandardCharsets.UTF_8);
    }

    /** GET with up to three tries; only GitHub hosts; the token goes to api.github.com only. */
    static byte[] get(String url, int max) throws IOException, InterruptedException {
        URI uri = URI.create(url);
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || !(host.equals("api.github.com") || host.equals("raw.githubusercontent.com"))) {
            throw new IOException("host not allowed: " + host);
        }
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpRequest.Builder req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "Lumora-Multiplayer-Mapping");
            if (host.equals("api.github.com")) {
                req.header("Accept", "application/vnd.github+json");
                String token = System.getenv("GITHUB_TOKEN");
                if (token != null && !token.isBlank()) {
                    req.header("Authorization", "Bearer " + token.strip());
                }
            }
            try {
                HttpResponse<InputStream> res = HTTP.send(req.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream in = res.body()) {
                    if (res.statusCode() == 200) {
                        byte[] data = in.readNBytes(max + 1);
                        if (data.length > max) {
                            throw new IOException("too large: " + url);
                        }
                        return data;
                    }
                    last = new IOException("HTTP " + res.statusCode() + " for " + url);
                }
            } catch (IOException e) {
                last = e;
            }
            Thread.sleep(2000L * (attempt + 1));
        }
        throw last;
    }

    static String blobSha(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(("blob " + data.length + "\0").getBytes(StandardCharsets.US_ASCII));
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(data)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // --- tiny JSON

    static String clip(String s, int max) {
        String t = s.strip().replaceAll("[\\p{Cntrl}]", "");
        return t.length() > max ? t.substring(0, max).strip() : t;
    }

    static String str(Object o) {
        return o instanceof String s ? s : "";
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : List.of();
    }

    static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String text) {
            Json j = new Json(text.startsWith("﻿") ? text.substring(1) : text);
            Object v = j.value(0);
            j.ws();
            if (j.i != j.s.length()) {
                throw j.fail("trailing data");
            }
            return v;
        }

        private Object value(int depth) {
            if (depth > 64) {
                throw fail("too deep");
            }
            ws();
            if (i >= s.length()) {
                throw fail("unexpected end");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{' -> {
                    i++;
                    Map<String, Object> m = new LinkedHashMap<>();
                    ws();
                    if (peek('}')) {
                        i++;
                        return m;
                    }
                    while (true) {
                        ws();
                        String k = string();
                        ws();
                        expect(':');
                        m.put(k, value(depth + 1));
                        ws();
                        if (peek(',')) {
                            i++;
                            continue;
                        }
                        expect('}');
                        return m;
                    }
                }
                case '[' -> {
                    i++;
                    List<Object> l = new ArrayList<>();
                    ws();
                    if (peek(']')) {
                        i++;
                        return l;
                    }
                    while (true) {
                        l.add(value(depth + 1));
                        ws();
                        if (peek(',')) {
                            i++;
                            continue;
                        }
                        expect(']');
                        return l;
                    }
                }
                case '"' -> {
                    return string();
                }
                default -> {
                    if (s.startsWith("true", i)) {
                        i += 4;
                        return Boolean.TRUE;
                    }
                    if (s.startsWith("false", i)) {
                        i += 5;
                        return Boolean.FALSE;
                    }
                    if (s.startsWith("null", i)) {
                        i += 4;
                        return null;
                    }
                    int start = i;
                    while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                        i++;
                    }
                    if (start == i) {
                        throw fail("unexpected character");
                    }
                    String n = s.substring(start, i);
                    return n.matches("-?\\d{1,18}") ? (Object) Long.parseLong(n) : (Object) Double.parseDouble(n);
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) {
                    throw fail("unterminated string");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> sb.append(e);
                }
            }
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw fail("expected '" + c + "'");
            }
            i++;
        }

        private IllegalArgumentException fail(String why) {
            return new IllegalArgumentException("JSON: " + why + " at " + i);
        }

        static String write(Object v) {
            StringBuilder sb = new StringBuilder();
            write(sb, v, 0);
            return sb.toString();
        }

        private static void write(StringBuilder sb, Object v, int indent) {
            if (v instanceof Map<?, ?> m) {
                if (m.isEmpty()) {
                    sb.append("{}");
                    return;
                }
                sb.append("{\n");
                int n = 0;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    sb.append("  ".repeat(indent + 1));
                    quote(sb, String.valueOf(e.getKey()));
                    sb.append(": ");
                    write(sb, e.getValue(), indent + 1);
                    sb.append(++n < m.size() ? ",\n" : "\n");
                }
                sb.append("  ".repeat(indent)).append('}');
            } else if (v instanceof List<?> l) {
                boolean flat = l.stream().allMatch(x -> x instanceof String || x instanceof Number);
                if (l.isEmpty() || flat) {
                    sb.append('[');
                    for (int k = 0; k < l.size(); k++) {
                        write(sb, l.get(k), indent);
                        sb.append(k + 1 < l.size() ? ", " : "");
                    }
                    sb.append(']');
                    return;
                }
                sb.append("[\n");
                for (int k = 0; k < l.size(); k++) {
                    sb.append("  ".repeat(indent + 1));
                    write(sb, l.get(k), indent + 1);
                    sb.append(k + 1 < l.size() ? ",\n" : "\n");
                }
                sb.append("  ".repeat(indent)).append(']');
            } else if (v instanceof String str) {
                quote(sb, str);
            } else if (v == null) {
                sb.append("null");
            } else {
                sb.append(v);
            }
        }

        private static void quote(StringBuilder sb, String s) {
            sb.append('"');
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            sb.append('"');
        }
    }
}
