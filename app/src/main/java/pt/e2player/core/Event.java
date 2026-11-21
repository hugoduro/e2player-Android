package pt.e2player.core;

/** Evento do EPG. start em segundos (epoch), dur em segundos. */
public final class Event {
    public final String ref, title, desc;
    public final long start, dur;

    public Event(String ref, String title, long start, long dur, String desc) {
        this.ref = ref;
        this.title = title;
        this.start = start;
        this.dur = dur;
        this.desc = desc;
    }

    public long end() {
        return start + dur;
    }

    public boolean isLive(long now) {
        return start <= now && now < start + dur;
    }

    public float progress(long now) {
        if (dur <= 0) return 0f;
        return Math.max(0f, Math.min(1f, (now - start) / (float) dur));
    }
}
