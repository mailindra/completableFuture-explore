package com.mailindra;

import jdk.jfr.Configuration;
import jdk.jfr.EventType;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class JfrPinningProbe implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(JfrPinningProbe.class);
    private final Recording recording;
    private final Path file;

    private JfrPinningProbe(Recording recording, Path file) throws Exception {
        this.recording = recording;
        this.file = file;

    }

    public static JfrPinningProbe start() throws Exception {
        // Load default settings then enable what we need
        var cfg = Configuration.getConfiguration("default");
        var rec = new Recording(cfg);
        // Keep recordings small & focused for tests
        rec.setDuration(null);
        rec.setDumpOnExit(false);
        rec.enable("jdk.VirtualThreadPinned").withPeriod(Duration.ofMillis(10)); // make sure it's on
        rec.enable("jdk.VirtualThreadSubmitFailed"); // optional, can be useful
        rec.start();
        // Use a unique temp file
        Path dest = Path.of(System.getProperty("java.io.tmpdir"),
                "pinning-" + System.nanoTime() + ".jfr");
        return new JfrPinningProbe(rec, dest);
    }

    /**
     * Stop, dump, and return all events of type jdk.VirtualThreadPinned.
     */
    public List<RecordedEvent> stopAndGetPinnedEvents() throws Exception {
        recording.stop();
        recording.dump(file);
        var events = RecordingFile.readAllEvents(file);
        return events.stream()
                .filter(e -> "jdk.VirtualThreadPinned".equals(e.getEventType().getName()))
                .collect(Collectors.toList());
    }

    public void printPinningEvents() throws IOException {
        recording.dump(file);
        var events = RecordingFile.readAllEvents(file);
        List<RecordedEvent> recordedEvents = events.stream()
                .filter(e -> "jdk.VirtualThreadPinned".equals(e.getEventType().getName()))
                .collect(Collectors.toList());
        if (!recordedEvents.isEmpty())
            summarize(recordedEvents);

    }

    public boolean hasPinningEvents() throws IOException {
        recording.dump(file);
        var events = RecordingFile.readAllEvents(file);
        return events.stream()
                .filter(e -> "jdk.VirtualThreadPinned".equals(e.getEventType().getName()))
                .findAny().isPresent();
    }

    private boolean isPinningEvent(RecordedEvent event) {
        // "jdk.VirtualThreadPinned" is emitted when a virtual thread is pinned
        return "jdk.VirtualThreadPinned".equals(event.getEventType().getName());
    }

    public String summarize(List<RecordedEvent> pinned) {
        StringBuilder sb = new StringBuilder();
        sb.append("Pinned events: ").append(pinned.size()).append("\n");
        for (int i = 0; i < pinned.size(); i++) {
            RecordedEvent e = pinned.get(i);
            EventType t = e.getEventType();
            sb.append("#").append(i + 1).append(" ").append(t.getName())
                    .append(" at ").append(e.getStartTime()).append("\n");
            // Useful attributes:
            // carrierThread, virtualThreadId, reason, etc.
            for (Map.Entry<String, Object> f : e.getFields().stream()
                    .collect(Collectors.toMap(f -> f.getName(), f -> e.getValue(f.getName()))).entrySet()) {
                sb.append("  ").append(f.getKey()).append(": ").append(f.getValue()).append("\n");
            }
            // Stack trace:
            if (e.getStackTrace() != null) {
                sb.append("  Stack:\n");
                e.getStackTrace().getFrames().stream().limit(15).forEach(fr ->
                        sb.append("    at ").append(fr.getMethod()).append(" (").append("the filename")
                                .append(":").append(fr.getLineNumber()).append(")\n"));
            }
        }
        return sb.toString();
    }

    @Override
    public void close() {
        try {
            recording.stop();
            recording.close();
        } catch (Exception ignored) {
        }
        try {
            java.nio.file.Files.deleteIfExists(file);
        } catch (Exception ignored) {
        }
    }
}
