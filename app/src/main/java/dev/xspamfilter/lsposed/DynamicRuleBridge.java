package dev.xspamfilter.lsposed;

import android.content.ContentValues;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import de.robv.android.xposed.XposedBridge;
import dev.xspamfilter.lsposed.data.HookBridgeContract;

/** Loads fully validated immutable snapshots from the module app. */
final class DynamicRuleBridge {
    private static final String TAG = "[XSF-RULES]";
    private static final char ALL_OF_SEPARATOR = '\u001F';
    private static final AtomicReference<Snapshot> ACTIVE = new AtomicReference<>();
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "xspamfilter-rule-bridge");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile Context targetContext;
    private static volatile ContentObserver snapshotObserver;
    private static volatile Handler heartbeatHandler;
    private static volatile String lastHeartbeatStatus = "STARTING";
    private static volatile long diagnosticUntil;
    private static final AtomicInteger PENDING_EVENTS = new AtomicInteger();

    private DynamicRuleBridge() {}

    static void installPackagedBootstrap(List<String> normalizedKeywords) {
        if (ACTIVE.get() != null) {
            return;
        }
        ArrayList<Rule> rules = new ArrayList<>(normalizedKeywords.size());
        long id = -1;
        for (String keyword : normalizedKeywords) {
            rules.add(Rule.literal(id--, "builtin", keyword, 100));
        }
        ACTIVE.compareAndSet(
                null,
                new Snapshot(0, Collections.unmodifiableList(rules), "packaged-bootstrap"));
        XposedBridge.log(TAG + " installed packaged bootstrap rules=" + rules.size());
    }

    static void initialize(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Application base context is null during attach");
        }
        // Application.getApplicationContext() is still null during attach. The attached base
        // context is already process-scoped and remains valid for the injected process lifetime.
        Context processContext = context;
        targetContext = processContext;
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        Handler mainHandler = new Handler(Looper.getMainLooper());
        ContentObserver observer = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                refreshAsync("content-observer");
            }
        };
        snapshotObserver = observer;
        processContext.getContentResolver().registerContentObserver(
                Uri.parse(HookBridgeContract.SNAPSHOT_URI),
                false,
                observer);
        heartbeatHandler = mainHandler;
        mainHandler.postDelayed(DynamicRuleBridge::sendPeriodicHeartbeat, 120_000L);
        refreshAsync("application-attach");
    }

    static Match firstMatch(String text) {
        Snapshot snapshot = ACTIVE.get();
        if (snapshot == null) {
            throw new IllegalStateException("No active rule snapshot");
        }
        String normalized = HookEntry.normalizeForRuleMatch(text);
        for (Rule rule : snapshot.rules) {
            String matched = rule.match(text == null ? "" : text, normalized);
            if (matched != null) {
                return new Match(rule, matched, snapshot.version);
            }
        }
        return null;
    }

    static void recordBlock(Match match, String postId, String text) {
        recordEvent("BLOCK", match, postId, text);
    }

    static boolean isDiagnosticActive() {
        return System.currentTimeMillis() < diagnosticUntil;
    }

    static void recordUnmatched(String postId, String text) {
        if (isDiagnosticActive()) {
            recordEvent("UNMATCHED", null, postId, text);
        }
    }

    private static void recordEvent(String action, Match match, String postId, String text) {
        Context context = targetContext;
        if (context == null) {
            XposedBridge.log(TAG + " cannot write block event before Application.attach");
            return;
        }
        if (PENDING_EVENTS.incrementAndGet() > 200) {
            PENDING_EVENTS.decrementAndGet();
            XposedBridge.log(TAG + " event queue full; diagnostic event rejected");
            return;
        }
        IO.execute(() -> {
            try {
                ContentValues values = new ContentValues();
                values.put("action", action);
                values.put("post_id", postId);
                values.put("surface", "timeline");
                values.put("preview", preview(text, 120));
                if (match != null) {
                    values.put("rule_id", match.rule.id);
                    values.put("source_id", match.rule.sourceId);
                    values.put("rule_pattern", match.rule.pattern);
                }
                Uri written = context.getContentResolver().insert(
                        Uri.parse(HookBridgeContract.EVENTS_URI),
                        values);
                if (written == null) {
                    throw new IllegalStateException("Event provider returned no result URI");
                }
            } catch (Throwable throwable) {
                XposedBridge.log(TAG + " failed to persist block event: " + throwable);
            } finally {
                PENDING_EVENTS.decrementAndGet();
            }
        });
    }

    private static void refreshAsync(String reason) {
        IO.execute(() -> refresh(reason));
    }

    private static void refresh(String reason) {
        ArrayList<Rule> candidate = new ArrayList<>();
        long version = -1;
        try (Cursor cursor = requireContext().getContentResolver().query(
                Uri.parse(HookBridgeContract.SNAPSHOT_URI),
                null,
                null,
                null,
                null)) {
            if (cursor == null) {
                throw new IllegalStateException("Rule snapshot provider returned a null cursor");
            }
            int versionColumn = cursor.getColumnIndexOrThrow("snapshot_version");
            int idColumn = cursor.getColumnIndexOrThrow("rule_id");
            int sourceColumn = cursor.getColumnIndexOrThrow("source_id");
            int kindColumn = cursor.getColumnIndexOrThrow("kind");
            int patternColumn = cursor.getColumnIndexOrThrow("pattern");
            int priorityColumn = cursor.getColumnIndexOrThrow("priority");
            int diagnosticColumn = cursor.getColumnIndexOrThrow("diagnostic_until");
            while (cursor.moveToNext()) {
                long rowVersion = cursor.getLong(versionColumn);
                if (version < 0) {
                    version = rowVersion;
                    diagnosticUntil = cursor.getLong(diagnosticColumn);
                } else if (version != rowVersion) {
                    throw new IllegalStateException("Snapshot provider returned mixed versions");
                }
                candidate.add(Rule.compile(
                        cursor.getLong(idColumn),
                        cursor.getString(sourceColumn),
                        cursor.getString(kindColumn),
                        cursor.getString(patternColumn),
                        cursor.getInt(priorityColumn)));
            }
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + " FAILED to load candidate snapshot; active snapshot unchanged");
            XposedBridge.log(throwable);
            sendHeartbeat("RULE_LOAD_ERROR");
            return;
        }
        if (candidate.isEmpty() || version < 1) {
            XposedBridge.log(TAG + " rejected empty or unversioned candidate snapshot");
            sendHeartbeat("RULE_LOAD_ERROR");
            return;
        }
        candidate.sort(Comparator.comparingInt(rule -> rule.priority));
        Snapshot next = new Snapshot(
                version,
                Collections.unmodifiableList(candidate),
                "content-provider:" + reason);
        ACTIVE.set(next);
        XposedBridge.log(TAG + " activated snapshot=" + version + " rules=" + candidate.size());
        sendHeartbeat("ACTIVE");
    }

    private static void sendHeartbeat(String status) {
        lastHeartbeatStatus = status;
        Context context = targetContext;
        if (context == null) return;
        Snapshot snapshot = ACTIVE.get();
        try {
            String versionName = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0)
                    .versionName;
            ContentValues values = new ContentValues();
            values.put("status", status);
            values.put("process", context.getApplicationInfo().processName);
            values.put("snapshot_version", snapshot == null ? 0 : snapshot.version);
            values.put("target_version", versionName);
            Uri written = context.getContentResolver().insert(
                    Uri.parse(HookBridgeContract.HEARTBEAT_URI),
                    values);
            if (written == null) {
                throw new IllegalStateException("Heartbeat provider returned no result URI");
            }
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + " failed to persist heartbeat: " + throwable);
        }
    }

    private static void sendPeriodicHeartbeat() {
        sendHeartbeat(lastHeartbeatStatus);
        Handler handler = heartbeatHandler;
        if (handler != null) {
            handler.postDelayed(DynamicRuleBridge::sendPeriodicHeartbeat, 120_000L);
        }
    }

    private static Context requireContext() {
        Context context = targetContext;
        if (context == null) {
            throw new IllegalStateException("Target process context is unavailable");
        }
        return context;
    }

    private static String preview(String text, int limit) {
        if (text == null || text.isEmpty()) return text;
        int end = text.offsetByCodePoints(0, Math.min(text.codePointCount(0, text.length()), limit));
        return text.substring(0, end);
    }

    static final class Match {
        final Rule rule;
        final String matchedText;
        final long snapshotVersion;

        Match(Rule rule, String matchedText, long snapshotVersion) {
            this.rule = rule;
            this.matchedText = matchedText;
            this.snapshotVersion = snapshotVersion;
        }
    }

    private static final class Snapshot {
        final long version;
        final List<Rule> rules;
        final String origin;

        Snapshot(long version, List<Rule> rules, String origin) {
            this.version = version;
            this.rules = rules;
            this.origin = origin;
        }
    }

    private static final class Rule {
        final long id;
        final String sourceId;
        final String kind;
        final String pattern;
        final int priority;
        final Pattern regex;
        final List<String> allOf;

        private Rule(
                long id,
                String sourceId,
                String kind,
                String pattern,
                int priority,
                Pattern regex,
                List<String> allOf) {
            this.id = id;
            this.sourceId = sourceId;
            this.kind = kind;
            this.pattern = pattern;
            this.priority = priority;
            this.regex = regex;
            this.allOf = allOf;
        }

        static Rule literal(long id, String sourceId, String pattern, int priority) {
            if (pattern == null || pattern.isEmpty()) {
                throw new IllegalArgumentException("Literal rule is empty");
            }
            return new Rule(id, sourceId, "LITERAL", pattern, priority, null, null);
        }

        static Rule compile(long id, String sourceId, String kind, String pattern, int priority) {
            if (kind == null || pattern == null || pattern.isEmpty()) {
                throw new IllegalArgumentException("Rule kind/pattern is missing for " + id);
            }
            switch (kind) {
                case "LITERAL":
                    String normalized = HookEntry.normalizeForRuleMatch(pattern);
                    if (normalized.isEmpty()) throw new IllegalArgumentException("Empty normalized literal " + id);
                    return literal(id, sourceId, normalized, priority);
                case "REGEX":
                    try {
                        return new Rule(
                                id,
                                sourceId,
                                kind,
                                pattern,
                                priority,
                                Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
                                null);
                    } catch (PatternSyntaxException exception) {
                        throw new IllegalArgumentException("Invalid regex rule " + id, exception);
                    }
                case "ALL_OF":
                    ArrayList<String> parts = new ArrayList<>();
                    for (String part : pattern.split(String.valueOf(ALL_OF_SEPARATOR), -1)) {
                        String normalizedPart = HookEntry.normalizeForRuleMatch(part);
                        if (!normalizedPart.isEmpty()) parts.add(normalizedPart);
                    }
                    if (parts.size() < 2) throw new IllegalArgumentException("ALL_OF rule needs two parts: " + id);
                    return new Rule(id, sourceId, kind, pattern, priority, null, Collections.unmodifiableList(parts));
                default:
                    throw new IllegalArgumentException("Unsupported rule kind " + kind);
            }
        }

        String match(String original, String normalized) {
            switch (kind) {
                case "LITERAL":
                    return normalized.contains(pattern) ? pattern : null;
                case "REGEX":
                    java.util.regex.Matcher matcher = regex.matcher(original);
                    return matcher.find() ? matcher.group() : null;
                case "ALL_OF":
                    for (String part : allOf) {
                        if (!normalized.contains(part)) return null;
                    }
                    return String.join(" + ", allOf);
                default:
                    throw new IllegalStateException("Unsupported compiled rule kind " + kind);
            }
        }
    }
}
