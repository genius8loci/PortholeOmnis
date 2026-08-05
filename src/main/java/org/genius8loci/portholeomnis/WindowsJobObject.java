package org.genius8loci.portholeomnis;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;

/**
 * Привязывает процессы Porthole к job object Windows с флагом KILL_ON_JOB_CLOSE.
 *
 * <p>Смысл: Windows сама убивает всё содержимое job'а, как только закрывается последний
 * его дескриптор. Дескриптор держит наша JVM, и закрывает его ядро — при любом способе
 * смерти процесса, включая {@code taskkill}, остановку из лаунчера и жёсткий вылет JVM.
 * Shutdown hook в этих случаях не отрабатывает, а job object работает всегда.
 *
 * <p>Всё делается «по возможности»: JNA приезжает транзитивно через зависимости Minecraft,
 * а не объявлена нами, поэтому любая осечка — отсутствие класса, отказ ядра, 32-битная
 * JVM — только пишет строку в лог. Подстраховкой остаются shutdown hook и добивание
 * осиротевших процессов при следующем запуске ({@link PortholeWatchdog}).
 */
final class WindowsJobObject {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
    private static final int JOBOBJECT_EXTENDED_LIMIT_INFORMATION_CLASS = 9;
    private static final int PROCESS_TERMINATE = 0x0001;
    private static final int PROCESS_SET_QUOTA = 0x0100;

    private static boolean initialised;
    /** Дескриптор job'а держится открытым всю жизнь JVM — в этом весь смысл. */
    private static Pointer job;

    private WindowsJobObject() {
    }

    /** Включает процесс в job. Молча ничего не делает там, где это недоступно. */
    static synchronized void adopt(Process process) {
        if (!WINDOWS) {
            return;
        }
        try {
            if (!initialised) {
                initialised = true;
                job = createJob();
            }
            if (job == null) {
                return;
            }
            Pointer handle = Kernel32.INSTANCE.OpenProcess(
                    PROCESS_TERMINATE | PROCESS_SET_QUOTA, false, (int) process.pid());
            if (handle == null) {
                PortholeOmnis.LOGGER.warn("[job] не удалось открыть процесс {}", process.pid());
                return;
            }
            try {
                if (Kernel32.INSTANCE.AssignProcessToJobObject(job, handle)) {
                    PortholeOmnis.LOGGER.info("[job] процесс {} умрёт вместе с игрой", process.pid());
                } else {
                    PortholeOmnis.LOGGER.warn("[job] не удалось привязать процесс {}", process.pid());
                }
            } finally {
                Kernel32.INSTANCE.CloseHandle(handle);
            }
        } catch (Throwable t) {
            // NoClassDefFoundError, UnsatisfiedLinkError, отказ ядра — всё сюда.
            PortholeOmnis.LOGGER.warn("[job] job object недоступен, остаётся добивание при перезапуске", t);
            job = null;
        }
    }

    private static Pointer createJob() {
        if (Native.POINTER_SIZE != 8) {
            // Раскладка ExtendedLimitInformation ниже посчитана под x64.
            PortholeOmnis.LOGGER.info("[job] не 64-битная JVM, пропускаем");
            return null;
        }
        Pointer handle = Kernel32.INSTANCE.CreateJobObjectW(null, null);
        if (handle == null) {
            PortholeOmnis.LOGGER.warn("[job] не удалось создать job object");
            return null;
        }
        ExtendedLimitInformation info = new ExtendedLimitInformation();
        info.limitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        if (!Kernel32.INSTANCE.SetInformationJobObject(handle,
                JOBOBJECT_EXTENDED_LIMIT_INFORMATION_CLASS, info, info.size())) {
            PortholeOmnis.LOGGER.warn("[job] не удалось выставить KILL_ON_JOB_CLOSE");
            Kernel32.INSTANCE.CloseHandle(handle);
            return null;
        }
        return handle;
    }

    /**
     * jna-platform 5.12.1 не объявляет функции job object, поэтому маппинг свой.
     * Имена совпадают с экспортом kernel32, переходник имён не нужен.
     */
    public interface Kernel32 extends StdCallLibrary {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        Pointer CreateJobObjectW(Pointer attributes, Pointer name);

        boolean SetInformationJobObject(Pointer job, int infoClass,
                                        Structure info, int infoLength);

        Pointer OpenProcess(int access, boolean inheritHandle, int pid);

        boolean AssignProcessToJobObject(Pointer job, Pointer process);

        boolean CloseHandle(Pointer handle);
    }

    /**
     * JOBOBJECT_EXTENDED_LIMIT_INFORMATION, разложенная в плоскую структуру: вложенные
     * BASIC_LIMIT_INFORMATION и IO_COUNTERS не содержат выравнивающих хвостов, так что
     * раскладка совпадает байт в байт. На x64 размер должен получиться 144.
     */
    @Structure.FieldOrder({
            "perProcessUserTimeLimit", "perJobUserTimeLimit", "limitFlags",
            "minimumWorkingSetSize", "maximumWorkingSetSize", "activeProcessLimit",
            "affinity", "priorityClass", "schedulingClass",
            "readOperationCount", "writeOperationCount", "otherOperationCount",
            "readTransferCount", "writeTransferCount", "otherTransferCount",
            "processMemoryLimit", "jobMemoryLimit",
            "peakProcessMemoryUsed", "peakJobMemoryUsed"
    })
    public static class ExtendedLimitInformation extends Structure {
        public long perProcessUserTimeLimit;
        public long perJobUserTimeLimit;
        public int limitFlags;
        public long minimumWorkingSetSize;
        public long maximumWorkingSetSize;
        public int activeProcessLimit;
        public long affinity;
        public int priorityClass;
        public int schedulingClass;

        public long readOperationCount;
        public long writeOperationCount;
        public long otherOperationCount;
        public long readTransferCount;
        public long writeTransferCount;
        public long otherTransferCount;

        public long processMemoryLimit;
        public long jobMemoryLimit;
        public long peakProcessMemoryUsed;
        public long peakJobMemoryUsed;
    }
}
