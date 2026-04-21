package ca.ibodrov.concord.mcp;

/*-
 * ~~~~~~
 * Concord MCP Server Plugin
 * ------
 * Copyright (C) 2026 Ivan Bodrov <ibodrov@gmail.com>
 * ------
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ======
 */

import static com.walmartlabs.concord.db.PgUtils.upperRange;
import static com.walmartlabs.concord.server.jooq.Tables.PROCESS_LOG_DATA;
import static com.walmartlabs.concord.server.jooq.Tables.PROCESS_LOG_SEGMENTS;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.max;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.walmartlabs.concord.db.MainDB;
import com.walmartlabs.concord.server.process.LogSegment;
import com.walmartlabs.concord.server.process.logs.ProcessLogAccessManager;
import com.walmartlabs.concord.server.process.logs.ProcessLogManager;
import com.walmartlabs.concord.server.process.logs.ProcessLogsDao.ProcessLog;
import com.walmartlabs.concord.server.process.logs.ProcessLogsDao.ProcessLogChunk;
import com.walmartlabs.concord.server.sdk.ProcessKey;
import com.walmartlabs.concord.server.sdk.ProcessStatus;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import org.jooq.Configuration;
import org.jooq.impl.DSL;

class ConcordLogTools {

    private static final int DEFAULT_READ_BYTES = 8 * 1024;
    private static final int DEFAULT_STREAM_BYTES = 16 * 1024;
    private static final int MAX_READ_BYTES = 256 * 1024;
    private static final int MAX_STREAM_DURATION_MS = 5 * 60 * 1000;
    private static final EnumSet<ProcessStatus> TERMINAL_STATUSES =
            EnumSet.of(ProcessStatus.FINISHED, ProcessStatus.FAILED, ProcessStatus.CANCELLED, ProcessStatus.TIMED_OUT);

    private final ProcessLogAccessManager logAccessManager;
    private final ProcessLogManager logManager;
    private final Configuration db;
    private final ConcordProcessTools processTools;

    @Inject
    ConcordLogTools(
            ProcessLogAccessManager logAccessManager,
            ProcessLogManager logManager,
            @MainDB Configuration db,
            ConcordProcessTools processTools) {

        this.logAccessManager = logAccessManager;
        this.logManager = logManager;
        this.db = db;
        this.processTools = processTools;
    }

    ProcessLogSegmentsResult listSegments(Map<String, Object> arguments, HttpServletRequest request) {
        var args = new ToolArguments(arguments);
        var instanceId = UUID.fromString(args.requireString("instanceId"));
        var includeSystem = args.optionalBoolean("includeSystem", true);
        var offset = Math.max(0, args.optionalInteger("offset", 0));
        var limit = Math.min(Math.max(1, args.optionalInteger("limit", 100)), 500);

        var processKey = logAccessManager.assertLogAccess(instanceId);
        var segments = logManager.listSegments(processKey, -1, 0).stream()
                .filter(segment -> includeSystem || segment.id() != 0)
                .skip(offset)
                .limit(limit)
                .map(ProcessLogSegmentInfo::from)
                .toList();

        return new ProcessLogSegmentsResult(true, "processLogSegments", instanceId.toString(), offset, limit, segments);
    }

    ProcessLogSegmentResult readSegment(Map<String, Object> arguments, HttpServletRequest request) {
        var args = new ToolArguments(arguments);
        var instanceId = UUID.fromString(args.requireString("instanceId"));
        var segmentId = args.requireLong("segmentId");
        var processKey = logAccessManager.assertLogAccess(instanceId);
        var window = readWindow(args, DEFAULT_READ_BYTES);
        var result = readSegment(processKey, segmentId, window);

        return new ProcessLogSegmentResult(
                true,
                "processLogSegment",
                instanceId.toString(),
                segmentId,
                result.startOffset(),
                result.endOffset(),
                result.size(),
                result.truncated(),
                result.text());
    }

    ProcessLogResult readLog(Map<String, Object> arguments, HttpServletRequest request) {
        var args = new ToolArguments(arguments);
        var instanceId = UUID.fromString(args.requireString("instanceId"));
        var format = LogFormat.from(args.optionalString("format"));
        var includeSystem = args.optionalBoolean("includeSystem", true);
        var processKey = logAccessManager.assertLogAccess(instanceId);
        var window = readWindow(args, DEFAULT_READ_BYTES);
        var result = readLog(processKey, window, format, includeSystem);

        return new ProcessLogResult(
                true,
                "processLog",
                instanceId.toString(),
                format.name().toLowerCase(Locale.ROOT),
                result.startOffset(),
                result.endOffset(),
                result.size(),
                result.truncated(),
                result.text());
    }

    ProcessLogStreamResult streamLog(Map<String, Object> arguments, McpSseWriter writer) {
        var args = new ToolArguments(arguments);
        var instanceId = UUID.fromString(args.requireString("instanceId"));
        var processKey = logAccessManager.assertLogAccess(instanceId);
        var format = LogFormat.from(args.optionalString("format"));
        var includeSystem = args.optionalBoolean("includeSystem", true);
        var segmentId = args.optionalLong("segmentId");
        var follow = args.optionalBoolean("follow", writer != null);
        var pollMillis = Math.min(Math.max(100, args.optionalInteger("pollMillis", 1000)), 10_000);
        var maxDurationMillis =
                Math.min(Math.max(1000, args.optionalInteger("maxDurationMillis", 60_000)), MAX_STREAM_DURATION_MS);
        var maxBytesPerPoll =
                Math.min(Math.max(1, args.optionalInteger("maxBytesPerPoll", DEFAULT_STREAM_BYTES)), MAX_READ_BYTES);
        var maxBufferedBytes =
                Math.min(Math.max(0, args.optionalInteger("maxBufferedBytes", 64 * 1024)), MAX_READ_BYTES);

        var offset = args.optionalInteger("startOffset", null);
        if (offset == null) {
            offset = args.optionalInteger("tailBytes", null) != null
                    ? Math.max(
                            0,
                            currentSize(processKey, segmentId) - args.optionalInteger("tailBytes", DEFAULT_READ_BYTES))
                    : 0;
        }
        var initialOffset = offset;

        var startedAt = System.nanoTime();
        var emittedChunks = 0;
        var emittedBytes = 0;
        var buffer = new StringBuilder();
        var lastResult = new ReadResult("", offset, offset, 0, false);

        do {
            var window = ReadWindow.from(offset, offset + maxBytesPerPoll);
            lastResult = segmentId != null
                    ? readSegment(processKey, segmentId, window)
                    : readLog(processKey, window, format, includeSystem);

            if (!lastResult.text().isEmpty()) {
                emittedChunks++;
                emittedBytes += lastResult.text().getBytes(StandardCharsets.UTF_8).length;
                offset = lastResult.endOffset();

                if (writer != null) {
                    writer.sendLogMessage(new ProcessLogStreamChunk(
                            instanceId.toString(),
                            segmentId,
                            format.name().toLowerCase(Locale.ROOT),
                            lastResult.startOffset(),
                            lastResult.endOffset(),
                            lastResult.size(),
                            lastResult.text()));
                }
                appendCapped(buffer, lastResult.text(), maxBufferedBytes);
            }

            if (!follow || isTerminal(instanceId)) {
                break;
            }

            if (Duration.ofNanos(System.nanoTime() - startedAt).toMillis() >= maxDurationMillis) {
                break;
            }

            if (lastResult.text().isEmpty()) {
                sleep(pollMillis);
            }
        } while (true);

        var terminal = isTerminal(instanceId);
        return new ProcessLogStreamResult(
                true,
                "processLogStream",
                instanceId.toString(),
                segmentId,
                format.name().toLowerCase(Locale.ROOT),
                initialOffset,
                offset,
                lastResult.size(),
                emittedChunks,
                emittedBytes,
                terminal,
                buffer.toString());
    }

    private ReadResult readSegment(ProcessKey processKey, long segmentId, ReadWindow window) {
        var log = logManager.segmentData(processKey, segmentId, window.start(), window.end());
        return toRawResult(log, window);
    }

    private ReadResult readLog(ProcessKey processKey, ReadWindow window, LogFormat format, boolean includeSystem) {
        if (format == LogFormat.PREFIXED) {
            return toPrefixedResult(processKey, window, includeSystem);
        }

        var log = logManager.get(processKey, window.start(), window.queryEnd());
        return toRawResult(log, window);
    }

    private ReadResult toRawResult(ProcessLog log, ReadWindow window) {
        var requestedStart = window.actualStart(log.getSize());
        var requestedEnd = window.actualEnd(log.getSize());
        var text = new StringBuilder();
        var actualStart = -1;
        var actualEnd = requestedStart;

        for (var chunk : log.getChunks()) {
            var clipped = clip(chunk, requestedStart, requestedEnd);
            if (clipped.length == 0) {
                continue;
            }

            if (actualStart < 0) {
                actualStart = Math.max(chunk.getStart(), requestedStart);
            }
            actualEnd = Math.max(actualEnd, Math.min(chunk.getStart() + chunk.getData().length, requestedEnd));
            text.append(new String(clipped, StandardCharsets.UTF_8));
        }

        if (actualStart < 0) {
            actualStart = requestedStart;
        }
        return new ReadResult(text.toString(), actualStart, actualEnd, log.getSize(), actualEnd < log.getSize());
    }

    private ReadResult toPrefixedResult(ProcessKey processKey, ReadWindow window, boolean includeSystem) {
        var size = currentSize(processKey);
        var requestedStart = window.actualStart(size);
        var requestedEnd = window.actualEnd(size);
        var chunks = prefixedChunks(processKey, requestedStart, requestedEnd, includeSystem);
        var text = new StringBuilder();
        var actualStart = -1;
        var actualEnd = requestedStart;

        for (var chunk : chunks) {
            var clipped = clip(chunk.start(), chunk.data(), requestedStart, requestedEnd);
            if (clipped.length == 0) {
                continue;
            }

            if (actualStart < 0) {
                actualStart = Math.max(chunk.start(), requestedStart);
            }
            actualEnd = Math.max(actualEnd, Math.min(chunk.start() + chunk.data().length, requestedEnd));
            appendPrefixed(text, chunk.segmentName(), new String(clipped, StandardCharsets.UTF_8));
        }

        if (actualStart < 0) {
            actualStart = requestedStart;
        }
        return new ReadResult(text.toString(), actualStart, actualEnd, size, actualEnd < size);
    }

    private int currentSize(ProcessKey processKey) {
        return currentSize(processKey, null);
    }

    private int currentSize(ProcessKey processKey, Long segmentId) {
        var range = segmentId != null ? PROCESS_LOG_DATA.SEGMENT_RANGE : PROCESS_LOG_DATA.LOG_RANGE;
        var upper = max(upperRange(range));
        var condition = PROCESS_LOG_DATA
                .INSTANCE_ID
                .eq(processKey.getInstanceId())
                .and(PROCESS_LOG_DATA.INSTANCE_CREATED_AT.eq(processKey.getCreatedAt()));
        if (segmentId != null) {
            condition = condition.and(PROCESS_LOG_DATA.SEGMENT_ID.eq(segmentId));
        }

        return DSL.using(db)
                .select(upper)
                .from(PROCESS_LOG_DATA)
                .where(condition)
                .fetchOptional(upper)
                .orElse(0);
    }

    private List<NamedChunk> prefixedChunks(
            ProcessKey processKey, int requestedStart, int requestedEnd, boolean includeSystem) {

        if (requestedEnd <= requestedStart) {
            return List.of();
        }

        var lowerRange = field("lower(" + PROCESS_LOG_DATA.LOG_RANGE + ")", Integer.class);
        var condition = PROCESS_LOG_DATA
                .INSTANCE_ID
                .eq(processKey.getInstanceId())
                .and(PROCESS_LOG_DATA.INSTANCE_CREATED_AT.eq(processKey.getCreatedAt()))
                .and(PROCESS_LOG_DATA.LOG_RANGE.getName() + " && int4range(?, ?)", requestedStart, requestedEnd);
        if (!includeSystem) {
            condition = condition.and(PROCESS_LOG_DATA.SEGMENT_ID.ne(0L));
        }

        return DSL.using(db)
                .select(
                        lowerRange,
                        PROCESS_LOG_DATA.CHUNK_DATA,
                        PROCESS_LOG_DATA.SEGMENT_ID,
                        PROCESS_LOG_SEGMENTS.SEGMENT_NAME)
                .from(PROCESS_LOG_DATA)
                .leftJoin(PROCESS_LOG_SEGMENTS)
                .on(PROCESS_LOG_SEGMENTS
                        .INSTANCE_ID
                        .eq(PROCESS_LOG_DATA.INSTANCE_ID)
                        .and(PROCESS_LOG_SEGMENTS.INSTANCE_CREATED_AT.eq(PROCESS_LOG_DATA.INSTANCE_CREATED_AT))
                        .and(PROCESS_LOG_SEGMENTS.SEGMENT_ID.eq(PROCESS_LOG_DATA.SEGMENT_ID)))
                .where(condition)
                .orderBy(PROCESS_LOG_DATA.LOG_RANGE)
                .fetch(record -> new NamedChunk(
                        record.value1(),
                        record.value2(),
                        record.value3(),
                        record.value4() != null ? record.value4() : "segment-" + record.value3()));
    }

    private boolean isTerminal(UUID instanceId) {
        var process = processTools.assertProcess(instanceId);
        return TERMINAL_STATUSES.contains(process.status());
    }

    private static byte[] clip(ProcessLogChunk chunk, int requestedStart, int requestedEnd) {
        return clip(chunk.getStart(), chunk.getData(), requestedStart, requestedEnd);
    }

    private static byte[] clip(byte[] data, int from, int to) {
        var result = new byte[to - from];
        System.arraycopy(data, from, result, 0, result.length);
        return result;
    }

    private static byte[] clip(int chunkStart, byte[] data, int requestedStart, int requestedEnd) {
        var from = Math.max(0, requestedStart - chunkStart);
        var to = Math.min(data.length, requestedEnd - chunkStart);
        if (to <= from) {
            return new byte[0];
        }
        return clip(data, from, to);
    }

    private static void appendPrefixed(StringBuilder out, String segmentName, String text) {
        if (text.isEmpty()) {
            return;
        }

        var atLineStart = true;
        for (var i = 0; i < text.length(); i++) {
            if (atLineStart) {
                out.append('[').append(segmentName).append("] ");
                atLineStart = false;
            }

            var c = text.charAt(i);
            out.append(c);
            if (c == '\n') {
                atLineStart = true;
            }
        }
    }

    private static void appendCapped(StringBuilder buffer, String text, int maxBytes) {
        if (maxBytes <= 0) {
            return;
        }

        var bytes = buffer.toString().getBytes(StandardCharsets.UTF_8).length
                + text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes <= maxBytes) {
            buffer.append(text);
        }
    }

    private static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while streaming process log", e);
        }
    }

    private static ReadWindow readWindow(ToolArguments args, int defaultBytes) {
        var maxBytes = Math.min(Math.max(1, args.optionalInteger("maxBytes", defaultBytes)), MAX_READ_BYTES);
        var start = args.optionalInteger("startOffset", null);
        var end = args.optionalInteger("endOffset", null);
        if (start != null) {
            if (end == null || end > start + maxBytes) {
                end = start + maxBytes;
            }
            return ReadWindow.from(start, Math.max(start, end));
        }

        var tailBytes = args.optionalInteger("tailBytes", maxBytes);
        return ReadWindow.tail(Math.min(Math.max(1, tailBytes), maxBytes));
    }

    private enum LogFormat {
        RAW,
        PREFIXED;

        static LogFormat from(String value) {
            if (value == null || value.isBlank()) {
                return RAW;
            }
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("'format' must be 'raw' or 'prefixed'");
            }
        }
    }

    private record ReadWindow(Integer start, Integer queryEnd, int tailBytes) {

        static ReadWindow from(int start, int end) {
            return new ReadWindow(start, end, -1);
        }

        static ReadWindow tail(int tailBytes) {
            return new ReadWindow(null, tailBytes, tailBytes);
        }

        int actualStart(int size) {
            return start != null ? start : Math.max(0, size - tailBytes);
        }

        int actualEnd(int size) {
            if (start == null) {
                return size;
            }
            return Math.min(queryEnd, size);
        }

        Integer end() {
            return start != null ? queryEnd : tailBytes;
        }
    }

    private record ReadResult(String text, int startOffset, int endOffset, int size, boolean truncated) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ProcessLogSegmentsResult(
            boolean ok,
            String entity,
            String instanceId,
            int offset,
            int limit,
            List<ProcessLogSegmentInfo> segments) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ProcessLogSegmentInfo(
            long id,
            String correlationId,
            String name,
            String status,
            String createdAt,
            String statusUpdatedAt,
            Integer warnings,
            Integer errors) {

        static ProcessLogSegmentInfo from(LogSegment segment) {
            return new ProcessLogSegmentInfo(
                    segment.id(),
                    segment.correlationId() != null ? segment.correlationId().toString() : null,
                    segment.name(),
                    segment.status() != null ? segment.status().name() : null,
                    segment.createdAt() != null ? segment.createdAt().toString() : null,
                    segment.statusUpdatedAt() != null
                            ? segment.statusUpdatedAt().toString()
                            : null,
                    segment.warnings(),
                    segment.errors());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ProcessLogSegmentResult(
            boolean ok,
            String entity,
            String instanceId,
            long segmentId,
            int startOffset,
            int endOffset,
            int size,
            boolean truncated,
            String text) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ProcessLogResult(
            boolean ok,
            String entity,
            String instanceId,
            String format,
            int startOffset,
            int endOffset,
            int size,
            boolean truncated,
            String text) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ProcessLogStreamResult(
            boolean ok,
            String entity,
            String instanceId,
            Long segmentId,
            String format,
            int startOffset,
            int endOffset,
            int size,
            int emittedChunks,
            int emittedBytes,
            boolean terminal,
            String text) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ProcessLogStreamChunk(
            String instanceId, Long segmentId, String format, int startOffset, int endOffset, int size, String text) {}

    private record NamedChunk(int start, byte[] data, long segmentId, String segmentName) {}
}
