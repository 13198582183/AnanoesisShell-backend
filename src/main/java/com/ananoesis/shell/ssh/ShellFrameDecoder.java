package com.ananoesis.shell.ssh;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OSC 1337 控制帧流式解析器（task 5.1）。
 *
 * <p>从终端输出流中识别并剥离 OSC 1337 控制帧，回调通知上层，
 * 同时返回干净的显示文本。</p>
 *
 * <h2>帧格式</h2>
 * <pre>
 * OSC 1337 ; type ; nonce ; command_id ; payload ST
 * OSC = ESC ] (0x1B 0x5D)
 * ST  = ESC \ (0x1B 0x5C) 或 BEL (0x07)
 * </pre>
 */
public class ShellFrameDecoder {

    private static final Logger LOG = LoggerFactory.getLogger(ShellFrameDecoder.class);

    /** 最大帧长度。design.md D3：有界解析。 */
    private static final int MAX_FRAME_LENGTH = 4096;

    private final String expectedNonce;
    private final Consumer<ShellFrame> frameConsumer;

    private enum State { NORMAL, ESC_PENDING, IN_OSC, OSC_ESC_SEEN }

    private State state = State.NORMAL;
    private final StringBuilder oscBuffer = new StringBuilder();
    private final StringBuilder textBuffer = new StringBuilder();

    public ShellFrameDecoder(String expectedNonce, Consumer<ShellFrame> frameConsumer) {
        if (expectedNonce == null || expectedNonce.isEmpty()) {
            throw new IllegalArgumentException("expectedNonce must not be empty");
        }
        this.expectedNonce = expectedNonce;
        this.frameConsumer = frameConsumer != null ? frameConsumer : f -> { };
    }

    /**
     * 处理一段终端输出，返回干净的显示文本。
     */
    public String decode(String data) {
        if (data == null || data.isEmpty()) {
            return "";
        }
        textBuffer.setLength(0);
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            processChar(c);
        }
        return textBuffer.toString();
    }

    private void processChar(char c) {
        switch (state) {
            case NORMAL:
                if (c == 0x1B) {
                    state = State.ESC_PENDING;
                } else {
                    textBuffer.append(c);
                }
                break;
            case ESC_PENDING:
                if (c == ']') {
                    oscBuffer.setLength(0);
                    state = State.IN_OSC;
                } else {
                    textBuffer.append((char) 0x1B);
                    textBuffer.append(c);
                    state = State.NORMAL;
                }
                break;
            case IN_OSC:
                if (c == 0x07) {
                    completeFrame();
                    state = State.NORMAL;
                } else if (c == 0x1B) {
                    state = State.OSC_ESC_SEEN;
                } else {
                    oscBuffer.append(c);
                    if (oscBuffer.length() > MAX_FRAME_LENGTH) {
                        LOG.debug("OSC frame exceeded max length {}, abandoning", MAX_FRAME_LENGTH);
                        textBuffer.append(oscBuffer);
                        oscBuffer.setLength(0);
                        state = State.NORMAL;
                    }
                }
                break;
            case OSC_ESC_SEEN:
                if (c == '\\') {
                    completeFrame();
                    state = State.NORMAL;
                } else if (c == ']') {
                    oscBuffer.append((char) 0x1B);
                    oscBuffer.append(c);
                    state = State.IN_OSC;
                } else {
                    oscBuffer.append((char) 0x1B);
                    oscBuffer.append(c);
                    state = State.IN_OSC;
                }
                break;
            default:
                break;
        }
    }

    private void completeFrame() {
        String content = oscBuffer.toString();
        oscBuffer.setLength(0);
        ShellFrame frame = parseFrame(content);
        if (frame != null) {
            if (expectedNonce.equals(frame.nonce())) {
                frameConsumer.accept(frame);
            } else {
                LOG.debug("Ignoring frame with wrong nonce: expected={} actual={}", expectedNonce, frame.nonce());
            }
        }
    }

    private ShellFrame parseFrame(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        String[] parts = content.split(";", 5);
        if (parts.length < 4) {
            LOG.debug("Frame has too few fields: {}", content);
            return null;
        }
        if (!"1337".equals(parts[0])) {
            LOG.debug("Not a 1337 frame, ignoring: {}", parts[0]);
            return null;
        }
        ShellFrameType type = parseFrameType(parts[1]);
        if (type == null) {
            LOG.debug("Unknown frame type, ignoring: {}", parts[1]);
            return null;
        }
        String nonce = parts[2];
        String commandId = parts[3];
        String payload = parts.length >= 5 ? parts[4] : "";
        return new ShellFrame(type, nonce, commandId, payload);
    }

    private static ShellFrameType parseFrameType(String typeStr) {
        if (typeStr == null) {
            return null;
        }
        switch (typeStr) {
            case "cmd_start": return ShellFrameType.CMD_START;
            case "cmd_end": return ShellFrameType.CMD_END;
            case "prompt": return ShellFrameType.PROMPT;
            case "cwd": return ShellFrameType.CWD;
            default: return null;
        }
    }
}
