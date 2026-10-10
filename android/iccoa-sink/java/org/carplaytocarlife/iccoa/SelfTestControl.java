package org.carplaytocarlife.iccoa;

import java.util.List;

/** Desktop self test for the control channel's framing. */
public final class SelfTestControl {

    private static int failures;

    public static void main(String[] args) {
        // a control message carrying a six byte HID touch report
        HidTouch.Touch touch = new HidTouch.Touch();
        touch.contactId = 1;
        touch.tip = true;
        touch.x = 800;
        touch.y = 400;
        List<Iap2.Parameter> parameters = new java.util.ArrayList<>();
        parameters.add(new Iap2.Parameter(0x0001, new byte[] {0x00, 0x01}));
        parameters.add(new Iap2.Parameter(0x0040, touch.report()));
        byte[] message = Iap2.control(0x1C00, parameters);

        Iap2Reader.ControlReader reader = new Iap2Reader.ControlReader();
        check("a partial message yields nothing", reader.feed(message, 10).isEmpty());
        List<Iap2.Message> decoded = reader.feed(java.util.Arrays.copyOfRange(message, 10,
                message.length), message.length - 10);
        check("the message decodes once complete", decoded.size() == 1);
        check("the message id is preserved", decoded.get(0).command == 0x1C00);
        check("both parameters are present", decoded.get(0).parameters.size() == 2);
        Iap2.Parameter report = decoded.get(0).find(0x0040);
        check("the HID report is carried in its parameter", report != null
                && report.value.length == HidTouch.REPORT_LENGTH);
        HidTouch.Touch back = HidTouch.decode(report.value, report.value.length);
        check("the touch survives the control channel",
                back.tip && back.x == 800 && back.y == 400 && back.contactId == 1);

        // two messages in one chunk, plus a trailing partial
        byte[] two = new byte[message.length * 2 + 3];
        System.arraycopy(message, 0, two, 0, message.length);
        System.arraycopy(message, 0, two, message.length, message.length);
        List<Iap2.Message> both = new Iap2Reader.ControlReader().feed(two, two.length);
        check("two messages in one chunk decode", both.size() == 2);

        Iap2Reader.ControlReader strict = new Iap2Reader.ControlReader();
        boolean rejected = false;
        byte[] bad = message.clone();
        bad[0] = 0x41;
        try {
            strict.feed(bad, bad.length);
        } catch (RuntimeException expected) {
            rejected = true;
        }
        check("a wrong marker is rejected", rejected);

        // a live control channel: connect, send, observe the touch callback
        final java.util.concurrent.atomic.AtomicReference<HidTouch.Touch> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        ControlChannel channel = new ControlChannel(new ControlChannel.Listener() {
            @Override public void onLog(String message) { Logs.add(message); }
            @Override public void onTouch(HidTouch.Touch decoded) {
                seen.set(decoded);
                latch.countDown();
            }
            @Override public void onMessage(Iap2.Message message) {
            }
        });
        try {
            channel.start();
            try (java.net.Socket socket = new java.net.Socket("127.0.0.1", channel.port())) {
                socket.getOutputStream().write(message);
                socket.getOutputStream().flush();
                boolean got = latch.await(3, java.util.concurrent.TimeUnit.SECONDS);
                check("the live channel reports a touch", got && seen.get() != null);
                if (seen.get() != null) {
                    check("the live touch has the right coordinates",
                            seen.get().x == 800 && seen.get().y == 400);
                }
                check("the channel counted the message", channel.messages() == 1);
                check("the channel counted the touch", channel.touches() == 1);
            }
        } catch (Exception error) {
            failures++;
            System.out.println("FAIL  live channel threw: " + error);
        } finally {
            channel.stop();
        }

        System.out.println("---- app log ----\n"
                + String.join("\n", Logs.snapshot()));
        System.out.println(failures == 0 ? "SELFTEST CONTROL OK" : ("SELFTEST CONTROL FAILED: " + failures));
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            System.out.println("PASS  " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name);
        }
    }
}
