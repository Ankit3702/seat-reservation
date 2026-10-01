package com.paytmmoney.seats.web;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * In-memory ring of the most recent structured request-log lines, exposed at
 * GET /logs so log access is public (free-tier platform logs need the owner's
 * login). Only the same JSON every request already emits is stored:
 * ts/method/path/status/latency/request_id — never headers or tokens.
 */
@Component
public class RequestLogBuffer {
    private final Deque<String> buf = new ArrayDeque<>();
    private static final int CAP = 1000;

    public synchronized void add(String line) {
        buf.addLast(line);
        while (buf.size() > CAP) buf.removeFirst();
    }

    public synchronized List<String> last(int n) {
        int k = Math.max(1, Math.min(n, CAP));
        List<String> out = new ArrayList<>(k);
        int skip = buf.size() - k;
        int i = 0;
        for (String s : buf) {
            if (i++ >= skip) out.add(s);
        }
        return out;
    }
}
