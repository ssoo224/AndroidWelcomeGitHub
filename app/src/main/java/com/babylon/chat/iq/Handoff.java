package com.babylon.chat.iq;

/**
 * جسر بين شاشة البداية (MainActivity) وشاشة المحادثات (MassActivity):
 * السبلاش تُطلق MassActivity مبكراً (شفافة ومخفية خلفها) فتتحقق من الدخول وتبني صفحة المحادثات أثناء رسم اللوگو،
 * وعند اكتمال اللوگو تكشفها فوراً بتلاشٍ دون انتظار أي بناء. كل الاستدعاءات على الخيط الرئيسي.
 */
final class Handoff {
    private Handoff() { }

    static final String EXTRA = "from_splash";

    private static boolean ready;          // اكتمل بناء MassActivity (جاهزة للكشف)
    private static boolean failed;         // انتهت MassActivity مبكراً (غير مسجّل / خطأ)
    private static MassActivity mass;
    private static Runnable onReady;       // ما تريده السبلاش عند الجاهزية
    private static Runnable onFailed;

    static void begin() { ready = false; failed = false; mass = null; onReady = null; onFailed = null; }

    /** تستدعيها MassActivity بعد اكتمال onCreate وأول رسم. */
    static void ready(MassActivity m) {
        mass = m; ready = true;
        Runnable r = onReady; onReady = null;
        if (r != null) r.run();
    }

    /** تستدعيها MassActivity إن أنهت نفسها قبل أن تصير جاهزة. */
    static void failed() {
        failed = true; ready = false; mass = null;
        Runnable r = onFailed; onFailed = null; onReady = null;
        if (r != null) r.run();
    }

    /** السبلاش تطلب الكشف: يُنفَّذ revealed فور الجاهزية (فوراً إن كانت جاهزة)، أو failure إن فشلت. */
    static void whenReady(Runnable revealed, Runnable failure) {
        if (failed) { if (failure != null) failure.run(); return; }
        if (ready && mass != null) { revealed.run(); return; }
        onReady = revealed; onFailed = failure;
    }

    static MassActivity mass() { return mass; }
}
