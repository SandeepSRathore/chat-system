package com.chatsystem.presence;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Minimal JDK WebSocket client that queues incoming text frames. */
final class WsTestClient implements WebSocket.Listener, AutoCloseable {

    private final LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
    private final StringBuilder partial = new StringBuilder();
    private final WebSocket socket;

    WsTestClient(String url) {
        this.socket = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create(url), this).join();
    }

    void send(String text) {
        socket.sendText(text, true).join();
    }

    /** Next frame containing {@code fragment}, skipping others; fails after 15 seconds. */
    String await(String fragment) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            String frame = frames.poll(200, TimeUnit.MILLISECONDS);
            if (frame != null && frame.contains(fragment)) {
                return frame;
            }
        }
        throw new AssertionError("No frame containing " + fragment);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            frames.add(partial.toString());
            partial.setLength(0);
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public void close() {
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
    }
}
