import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

/**
 *
 * @author Česnek Michal, UNIDATAZ s.r.o.
 */
public class watchdog {
    
    public static class Test{
        public static void main(String[] args) {
            reanalyseIntervals(new File("./watchdog.log"));
        }
    }
    
    //Počet celkových minutových cyklů od spuštění programu
    public static long allTime = 0;
    
    public static void main(String[] args) {
        ScheduledExecutorService pool = Executors.newScheduledThreadPool(1);
        Runnable r = new Runnable() {
            Long ntp = null;
            long noTime = 0;
            @Override
            public void run() {
                allTime++;
                if(ntp==null){
                    ntp = getNTPDateTimeOffsetMilis();
                    System.out.println("NTP time diff: "+ntp+"ms");
                    if(ntp!=null){
                        setSystemDateAndTime(ntp);
                        for (long mins = noTime; mins >= 0; mins--) {
                            writeTime(System.currentTimeMillis()-(mins*60_000));
                        }
                    } else {
                        noTime++;
                        return;
                    }
                } else {
                    writeTime(System.currentTimeMillis());
                }
                uploadTimes();
                uploadRsync();
            }
        };
        try {
            pool.scheduleAtFixedRate(r, 0L, 60L, TimeUnit.SECONDS);
            if(isWindows()){
                System.out.println("[PRESS ENTER TO EXIT]");
                Scanner sc = new Scanner(System.in);
                String userOption = sc.nextLine();
                pool.shutdown();
            }
            while (!pool.awaitTermination(1L, TimeUnit.SECONDS)) {
                sleep(1000);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        System.exit(0);
    }
    
    private static boolean uploadRsync(){
        File f1 = new File("/var/log/rsync.log");
        if(f1.exists()){
            //tail -n 20 /var/log/rsync.log > /opt/rsync.log
            String lines = CMD.call("tail","-n","20","/var/log/rsync.log");
            if(lines==null) return false;
            File f2 = new File("/opt/rsync.log");
            f2.delete();
            addLineToFile(f2, lines);
            int res = CMD.callRV("curl","--user","dd-wrt:dd-wrt","--upload-file","/opt/rsync.log","smb://dd-wrt/usb/");
            return res==0;
        } else {
            System.err.println("File "+f1+" not exist!");
            return false;
        }
    }
    
    private static boolean uploadTimes(){
        //Spustíme reanalýzu intervalů
        reanalyseIntervals(null);
        //Pokusíme se stáhnout vzdálený soubor
        int res = CMD.callRV("curl","--user","dd-wrt:dd-wrt","-o","/opt/watchdog-remote.log","smb://dd-wrt/usb/watchdog.log");
        //Podaří-li se stáhnout vzdálený soubor
        if(res==0){
            //odstraníme jej a pokračujeme v uploadu
            new File("/opt/watchdog-remote.log").delete();
        } else 
        //Pokud byl vzdálený sobor odstraněn resp. neexistuje
        if(res==78){
            //Lokálně jej promažeme
            logFile.delete();
            //Vložíme zpět všechny časy od spuštění programu
            for (long mins = allTime; mins >= 0; mins--) {
                writeTime(System.currentTimeMillis()-(mins*60_000));
            }
            //Vynulujeme počítadlo allTime
            allTime = 0;
            //Spustíme reanalýzu intervalů
            reanalyseIntervals(null);
        } 
        //Pokud stažení souboru nešlo udělat, nemá smysl dál něco uploadovat
        else {
            return false;
        }
        
        //Upload log souborĹŻ
        //watchdog.log
        res = CMD.callRV("curl","--user","dd-wrt:dd-wrt","--upload-file","/opt/watchdog.log","smb://dd-wrt/usb/");
        if(res!=0) return false;
        
        //watchdog2.log
        res = CMD.callRV("curl","--user","dd-wrt:dd-wrt","--upload-file","/opt/watchdog2.log","smb://dd-wrt/usb/");
        if(res!=0) return false;
        
        return true;
    }
    
    public static void reanalyseIntervals(File logFile) {
        try {
            File fileIn = logFile!=null ? logFile : watchdog.logFile;
            List<String> lines = Files.readAllLines(fileIn.toPath());
            TreeSet<Long> ts = new TreeSet<>();
            for (String line : lines) {
                Date date = parse(line);
                if (date == null) {
                    continue;
                }
                long mins = date.getTime() / 60000L;
                ts.add(Long.valueOf(mins));
            }
            ;
            ArrayList<LongIntervalAnalysisUtil.Interval> intervals = LongIntervalAnalysisUtil.getIntervals(ts.toArray(Long[]::new));
            SimpleDateFormat f = sdf;
            File fileOut = logFile!=null ? new File(logFile.getParentFile(),log2File.getName()) : log2File;
            fileOut.delete();
            fileOut.createNewFile();
            //Kratší intervaly než 5 minut odstraníme
            intervals.removeIf((i)->i.getRight() - i.getLeft() < 5L);
            //Vyexportujeme intervaly
            for (LongIntervalAnalysisUtil.Interval i : intervals) {
                Date from = new Date(i.getLeft()  * 60000L);
                Date to   = new Date(i.getRight() * 60000L);
                String out = f.format(from) + " <-> " + f.format(to);
                addLineToFile(fileOut,out);
                System.out.println(out);
            }
            //Vyexportujeme po-pa, so-ne
            Function<Long, String> l = (Long t) -> (t<=9 ? "0"+t : ""+t);
            BiConsumer<Boolean,TreeSet<Long>> export = (Boolean poPa,TreeSet<Long> s) -> {
                if(s==null || s.isEmpty()) return;
                ArrayList<LongIntervalAnalysisUtil.Interval> its = LongIntervalAnalysisUtil.getIntervals(s.toArray(Long[]::new));
                String out = poPa==null ? "N / A:" : (poPa ? "Po-Pa:" : "So-Ne:");
                for (LongIntervalAnalysisUtil.Interval i : its) {
                    out += l.apply(i.left/60)+":"+l.apply(i.left%60) +"-"+ l.apply(i.right/60)+":"+l.apply(i.right%60);
                    if(i!=its.get(its.size()-1)) out += ", ";
                }
                addLineToFile(fileOut,out);
                if(poPa!=null){
                    System.out.println(out);
                } else {
                    System.err.println(out);
                }
            };
            Boolean poPaPre = null;
            ts.clear();
            for (LongIntervalAnalysisUtil.Interval i : intervals) {
                Date from = new Date(i.getLeft()  * 60000L);
                Date to   = new Date(i.getRight() * 60000L);
                i = new LongIntervalAnalysisUtil.Interval(DateUtil.getMinutesFromMidnight(from), DateUtil.getMinutesFromMidnight(to));
                Boolean poPa = DateUtil.isPoPa(from, to) ? Boolean.TRUE  : 
                            (DateUtil.isSoNeSv(from, to) ? Boolean.FALSE : (Boolean)null );
                if(poPa!=poPaPre) {
                    export.accept(poPaPre,ts);
                    ts.clear();
                    ts.addAll(i.valuesList());
                    poPaPre = poPa;
                } else {
                    ts.addAll(i.valuesList());
                }
            }
            export.accept(poPaPre,ts);
            
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public static Date parse(String text) {
        try {
            return sdf.parse(text.trim());
        } catch (Exception e) {
            //e.printStackTrace();
            return null;
        }
    }
    
    private static final SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy HH:mm");
    private static final File logFile = new File("/opt/watchdog.log");
    private static final File log2File = new File("/opt/watchdog2.log");
    
    private static final void writeTime(long currentTimeMilis){
        String dateTimeString = sdf.format(new Date(currentTimeMilis));
        addLineToFile(logFile, dateTimeString);
        System.out.println(dateTimeString);
    }
    
    public static void addLineToFile(File f, String line) {
        try {
            Files.write(f.toPath(), (line+"\r\n").getBytes(), new OpenOption[]{StandardOpenOption.CREATE,StandardOpenOption.APPEND});
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private static final void setSystemDateAndTime(Long dateTimeOffsetMilis){
        if(dateTimeOffsetMilis==null || dateTimeOffsetMilis==0) return;
        if(isWindows()) {//ON WINDOWS
            SimpleDateFormat dateFormatter = new SimpleDateFormat("dd-MM-yy");
            SimpleDateFormat timeFormatter = new SimpleDateFormat("HH:mm:ss");
            Date dateTime = new Date(System.currentTimeMillis()+dateTimeOffsetMilis);
            {//SETTING TIME
                String[] cmds = new String[]{"cmd.exe","/C","time",timeFormatter.format(dateTime)};
                String output = CMD.call(cmds);
//                System.out.println(Arrays.stream(cmds).collect(Collectors.joining(" ")));
//                System.out.println(output);
            }
            {//SETTING DATE
                String[] cmds = new String[]{"cmd.exe","/C","date",dateFormatter.format(dateTime)};
                String output = CMD.call(cmds);
//                System.out.println(Arrays.stream(cmds).collect(Collectors.joining(" ")));
//                System.out.println(output);
            }
        } else 
        if(isLinux()) {//ON LINUX
            SimpleDateFormat dateTimeFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            Date dateTime = new Date(System.currentTimeMillis()+dateTimeOffsetMilis);
            String[] cmds = new String[]{"date","-s",dateTimeFormatter.format(dateTime)};
            String output = CMD.call(cmds);
//            System.out.println(Arrays.stream(cmds).collect(Collectors.joining(" ")));
//            System.out.println(output);
        }
    }
    
    
    private static final String SERVER = "pool.ntp.org";
    private static final int    PORT = 123;
    private static NtpMessage getNtpMessage() {
        InetAddress address = null;
        try(DatagramSocket socket = new DatagramSocket()){
            socket.setSoTimeout(5000);
            address = InetAddress.getByName(SERVER);
            final byte[] buffer = new NtpMessage().toByteArray();
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, address, PORT);
            socket.send(packet); 
            packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            return new NtpMessage(packet.getData());
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }
    }

    private static Date getNTPDate() {
        NtpMessage msg = getNtpMessage();
        return msg!=null ? NtpMessage.timestampToDate(msg.transmitTimestamp) : null;
    }
    
    private static Long getNTPDateTimeOffsetMilis() {
        NtpMessage msg = getNtpMessage();
        if(msg==null) return null;
        return Math.round((msg.transmitTimestamp-msg.originateTimestamp)*1000d);
    }
    
    //<editor-fold defaultstate="collapsed" desc="class DateUtil">
    public static class DateUtil{
        /** Spočítá a vrátí počet minut uběhlých od půnoci zadaného dne. */
        public static long getMinutesFromMidnight(Date date){
            Calendar c = toC(date);
            return c.get(Calendar.HOUR_OF_DAY)*60+c.get(Calendar.MINUTE);
        }
        public static boolean isPoPa(Date from,Date to){
            return isWeekday(from) && !isHollyday(from) && isWeekday(to) && !isHollyday(to);
        }
        public static boolean isSoNeSv(Date from,Date to){
            return (isWeekend(from) || isHollyday(from)) && (isWeekend(to) || isHollyday(to));
        }
        /** Je zadané datum sobotou či nedělí? */
        public static boolean isWeekend(Date date){
            return equalAny(toLD(date).getDayOfWeek(), DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
        }
        /** Je zadané datum od pondělí do neděle? */
        public static boolean isWeekday(Date date){
            return equalAny(toLD(date).getDayOfWeek(), DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
        }
        /** Je zadané datum známým datem nějakého svátku? */
        public static boolean isHollyday(Date date){
            LocalDate c = toLD(date);
            for (Integer[] hollyday : hollydays) {
                if(hollyday==null) continue;
                int idx = 0;
                if(hollyday.length>idx && hollyday[idx]!=null){
                    if(c.getDayOfMonth()==hollyday[idx]){
                        idx++;
                        if(hollyday.length>idx && hollyday[idx]!=null){
                            if(c.getMonthValue()==hollyday[idx]){
                                idx++;
                                if(hollyday.length>idx && hollyday[idx]!=null){
                                    if(c.getYear()==hollyday[idx]){
                                        //Kriterium je den, měsíc, rok a ty sedí
                                        return true;
                                    }
                                } else {
                                    //Kriterium je den, měsíc a ty sedí
                                    return true;
                                }
                            }
                        } else {
                            //Kriterium je pouze den a ten sedí
                            return true;
                        }
                    }
                }
            }
            return false;
        }
        private static final Integer[][] hollydays = arr(
            dmy(1,1),//Nový rok
            dmy(1,5),//Svátek práce
            dmy(8,5),//Den vítězství
            dmy(5,7),//Den slovanských věrozvěstů Cyrila a Metoděje
            dmy(6,7),//Den upálené mistra Jana Husa
            dmy(28,9),//Den české státnosti
            dmy(28,10),//Den vzniku Československa
            dmy(17,11),//Den boje za svobodu a demokracii 
            dmy(24,12),//Štědrý den
            dmy(25,12),//1. svátek vánoční
            dmy(26,12),//2. svátek vánoční
            dmy(2,4,2021),//Velký pátek
            dmy(5,4,2021),//Velikonoční pondělí
            dmy(15,4,2022),//Velký pátek
            dmy(18,4,2022),//Velikonoční pondělí
            dmy(07,4,2023),//Velký pátek
            dmy(10,4,2023),//Velikonoční pondělí
            dmy(29,3,2024),//Velký pátek
            dmy(01,4,2024),//Velikonoční pondělí
            dmy(18,4,2025),//Velký pátek
            dmy(21,4,2025),//Velikonoční pondělí
            dmy(03,4,2026),//Velký pátek
            dmy(06,4,2026),//Velikonoční pondělí
            dmy(26,3,2027),//Velký pátek
            dmy(29,3,2027),//Velikonoční pondělí
            dmy(14,4,2028),//Velký pátek
            dmy(17,4,2028),//Velikonoční pondělí
            dmy(30,3,2029),//Velký pátek
            dmy(02,4,2029),//Velikonoční pondělí
            dmy(19,4,2030),//Velký pátek
            dmy(22,4,2030),//Velikonoční pondělí
            null
        );
        public static Integer[] dmy(Integer... dayMonthYear){
            return dayMonthYear;
        }
        public static Calendar toC(Date date){
            Calendar c = Calendar.getInstance();
            c.setTime(date);
            return c;
        }
        public static LocalDate toLD(Date date){
            return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }
        public static Integer[][] arr(Integer[]... hollydays){
            return hollydays;
        }
        public static <T> boolean equal(T a,T b){
            return Objects.equals(a, b);
        }
        public static <T> boolean equalAny(T a,T... b){
            for (T c : b) {
                if(Objects.equals(a, c)) return true;
            }
            return false;
        }
    }
    //</editor-fold>
    
    //<editor-fold defaultstate="collapsed" desc="class LongIntervalAnalysisUtil">
    public static class LongIntervalAnalysisUtil {
        
        public boolean addValue(Long value) {
            return this.longSet.add(value);
        }
        
        public boolean addValues(ArrayList<Long> values) {
            return this.longSet.addAll(values);
        }
        
        public boolean addValues(Long[] values) {
            return this.longSet.addAll(Arrays.asList(values));
        }
        
        public String getFormatedIntervals() {
            return format(getIntervals());
        }
        
        public static Interval join(Interval a, Interval b){
            if(a==null) return b;
            if(b==null) return a;
            return new Interval(Math.min(a.left, b.left), Math.max(a.right, b.right));
        }
        
        public ArrayList<Interval> getIntervals() {
            ArrayList<Interval> intervals = new ArrayList<>();
            ArrayList<Long> integerList = new ArrayList<>(this.longSet);
            Collections.sort(integerList);
            Long left = null;
            Long right = null;
            for (Long integer : integerList) {
                if (left == null) {
                    left = integer;
                    right = integer;
                    continue;
                }
                if (right.longValue() == integer.longValue() - 1L) {
                    right = integer;
                    continue;
                }
                intervals.add(new Interval(left.longValue(), right.longValue()));
                left = integer;
                right = integer;
            }
            
            if (left != null && right != null) {
                intervals.add(new Interval(left.longValue(), right.longValue()));
            }
            return intervals;
        }
        
        private HashSet<Long> longSet = new HashSet<>();
        
        public static class Interval {
            
            private long left;
            private long right;
            
            public Interval(long left, long right) {
                assert left <= right;
                this.left = left;
                this.right = right;
            }
            
            public long getLeft() {
                return this.left;
            }
            
            public long getRight() {
                return this.right;
            }
            
            public List<Long> valuesList(){
                return LongStream.rangeClosed(left, right).boxed().collect(Collectors.toList());
            }
            
            public String toString() {
                return "[" + this.left + ", " + this.right + "]";
            }
        }
        
        public static String format(ArrayList<Interval> intervals) {
            if (intervals == null) {
                return null;
            }
            if (intervals.isEmpty()) {
                return "";
            }
            return intervals.stream()
                    .map(i -> (i.getLeft() == i.getRight()) ? (i.getLeft() + "") : (i.getLeft() + "-" + i.getRight()))
                    .collect(Collectors.joining(", "));
        }
        
        public static ArrayList<Interval> getIntervals(Long[] values) {
            LongIntervalAnalysisUtil iiau = new LongIntervalAnalysisUtil();
            iiau.addValues(values);
            return iiau.getIntervals();
        }
    }
    //</editor-fold>
    
    //<editor-fold defaultstate="collapsed" desc="class Interval">
    public static class Interval {
        
        private long left;
        private long right;
        
        public Interval(long left, long right) {
            assert left <= right;
            this.left = left;
            this.right = right;
        }
        
        public long getLeft() {
            return this.left;
        }
        
        public long getRight() {
            return this.right;
        }
        
        public String toString() {
            return "[" + this.left + ", " + this.right + "]";
        }
    }
    //</editor-fold>
    
    //<editor-fold defaultstate="collapsed" desc="class CMD">
    public static class CMD{
//        public static void main(String[] args) throws IOException {
//            Process p = Runtime.getRuntime().exec(args);
//            System.out.println(IS.toString(p.getInputStream()));
//            System.err.println(IS.toString(p.getErrorStream()));
//        }
        public static String call(String... cmds) {
            try {
                Process p = Runtime.getRuntime().exec(cmds);
                if(!p.waitFor(1, TimeUnit.MINUTES)) {
                    //timeout - kill the process. 
                    p.destroy(); // consider using destroyForcibly instead
                }
                String sout = IS.toString(p.getInputStream());
                String serr = IS.toString(p.getErrorStream());
                if(serr!=null && !serr.isEmpty()) System.err.println(serr);
                return sout+serr;
            } catch (IOException | InterruptedException ex) {
                ex.printStackTrace();
                return null;
            }
        }
        public static int callRV(String... cmds) {
            try {
                Process p = Runtime.getRuntime().exec(cmds);
                if(!p.waitFor(1, TimeUnit.MINUTES)) {
                    //timeout - kill the process. 
                    p.destroy(); // consider using destroyForcibly instead
                }
                String sout = IS.toString(p.getInputStream());
                String serr = IS.toString(p.getErrorStream());
                int res = p.exitValue();
                if(res!=0) System.out.println(sout+serr);
                return res;
            } catch (IOException ex) {
                ex.printStackTrace();
                return -1;
            } catch (InterruptedException ex) {
                return -2;
            }
        }
    }
    //</editor-fold>
    
    //<editor-fold defaultstate="collapsed" desc="class IS">
    public static class IS {
//        public static void main(String[] args) throws IOException {
//            String in = toString(System.in);
//            System.out.println(in);
//            for (String arg : args) {
//                System.out.println(arg);
//            }
//        }
        public static String toString(InputStream is) throws IOException{
            StringBuilder textBuilder = new StringBuilder();
            try (Reader reader = new BufferedReader(new InputStreamReader(is, Charset.forName(StandardCharsets.UTF_8.name())))) {
                int c = 0;
                while ((c = reader.read()) != -1) {
                    textBuilder.append((char) c);
                }
            }
            return textBuilder.toString();
        }
    }
    //</editor-fold>
    
    //<editor-fold defaultstate="collapsed" desc="class NtpMessage">
    /**
     * This class represents a NTP message, as specified in RFC 2030.  The message
     * format is compatible with all versions of NTP and SNTP.
     *
     8  * This class does not support the optional authentication protocol, and
     9  * ignores the key ID and message digest fields.
    10  * 
    11  * For convenience, this class exposes message values as native Java types, not
    12  * the NTP-specified data formats.  For example, timestamps are
    13  * stored as doubles (as opposed to the NTP unsigned 64-bit fixed point
    14  * format).
    15  * 
    16  * However, the contructor NtpMessage(byte[]) and the method toByteArray()
    17  * allow the import and export of the raw NTP message format.
    18  * 
    19  * 
    20  * Usage example
    21  * 
    22  * // Send message
    23  * DatagramSocket socket = new DatagramSocket();
    24  * InetAddress address = InetAddress.getByName( ntp.cais.rnp.br );
    25  * byte[] buf = new NtpMessage().toByteArray();
    26  * DatagramPacket packet = new DatagramPacket(buf, buf.length, address, 123);
    27  * socket.send(packet);
    28  * 
    29  * // Get response
    30  * socket.receive(packet);
    31  * System.out.println(msg.toString());
    32  * 
    33  *  
    34  * This code is copyright (c) Adam Buckley 2004
    35  *
    36  * This program is free software; you can redistribute it and/or modify it 
    37  * under the terms of the GNU General Public License as published by the Free 
    38  * Software Foundation; either version 2 of the License, or (at your option) 
    39  * any later version.  A HTML version of the GNU General Public License can be
    40  * seen at http://www.gnu.org/licenses/gpl.html
    41  *
    42  * This program is distributed in the hope that it will be useful, but WITHOUT 
    43  * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or 
    44  * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for 
    45  * more details.
    46  * 
    47  * 
    48  * Comments for member variables are taken from RFC2030 by David Mills,
    49  * University of Delaware.
    50  * 
    51  * Number format conversion code in NtpMessage(byte[] array) and toByteArray()
    52  * inspired by http://www.pps.jussieu.fr/~jch/enseignement/reseaux/
    53  * NTPMessage.java which is copyright (c) 2003 by Juliusz Chroboczek
    54  * 
    55  * @author Adam Buckley
    56  */

    public static class NtpMessage {

       /**
    61     * This is a two-bit code warning of an impending leap second to be
    62     * inserted/deleted in the last minute of the current day.  It's values
    63     * may be as follows:
    64     * 
    65     * Value     Meaning
    66     * -----     -------
    67     * 0         no warning
    68     * 1         last minute has 61 seconds
    69     * 2         last minute has 59 seconds)
    70     * 3         alarm condition (clock not synchronized)
    71     */
       public byte leapIndicator = 0;


       /**
    76     * This value indicates the NTP/SNTP version number.  The version number
    77     * is 3 for Version 3 (IPv4 only) and 4 for Version 4 (IPv4, IPv6 and OSI).
    78     * If necessary to distinguish between IPv4, IPv6 and OSI, the
    79     * encapsulating context must be inspected.
    80     */
       public byte version = 3;


       /**
    85      * This value indicates the mode, with values defined as follows:
    86      * 
    87      * Mode     Meaning
    88      * ----     -------
    89      * 0        reserved
    90      * 1        symmetric active
    91      * 2        symmetric passive
    92      * 3        client
    93      * 4        server
    94      * 5        broadcast
    95      * 6        reserved for NTP control message
    96      * 7        reserved for private use
    97      * 
    98      * In unicast and anycast modes, the client sets this field to 3 (client)
    99      * in the request and the server sets it to 4 (server) in the reply. In
   100      * multicast mode, the server sets this field to 5 (broadcast).
   101      */
      public byte mode = 0;


       /**
   106     * This value indicates the stratum level of the local clock, with values
   107     * defined as follows:
   108     * 
   109     * Stratum  Meaning
   110     * ----------------------------------------------
   111     * 0        unspecified or unavailable
   112     * 1        primary reference (e.g., radio clock)
   113     * 2-15     secondary reference (via NTP or SNTP)
   114     * 16-255   reserved
   115     */
       public short stratum = 0;


       /**
   120     * This value indicates the maximum interval between successive messages,
   121     * in seconds to the nearest power of two. The values that can appear in
   122     * this field presently range from 4 (16 s) to 14 (16284 s); however, most
   123     * applications use only the sub-range 6 (64 s) to 10 (1024 s).
   124     */
       public byte pollInterval = 0;


       /**
   129     * This value indicates the precision of the local clock, in seconds to
   130     * the nearest power of two.  The values that normally appear in this field
   131     * range from -6 for mains-frequency clocks to -20 for microsecond clocks
   132     * found in some workstations.
   133     */
       public byte precision = 0;


       /**
   138     * This value indicates the total roundtrip delay to the primary reference
   139     * source, in seconds.  Note that this variable can take on both positive
   140     * and negative values, depending on the relative time and frequency
   141     * offsets. The values that normally appear in this field range from
   142     * negative values of a few milliseconds to positive values of several
   143     * hundred milliseconds.
   144     */
       public double rootDelay = 0;


       /**
   149     * This value indicates the nominal error relative to the primary reference
   150     * source, in seconds.  The values  that normally appear in this field
   151     * range from 0 to several hundred milliseconds.
   152     */
       public double rootDispersion = 0;


       /**
   157     * This is a 4-byte array identifying the particular reference source.
   158     * In the case of NTP Version 3 or Version 4 stratum-0 (unspecified) or
   159     * stratum-1 (primary) servers, this is a four-character ASCII string, left
   160     * justified and zero padded to 32 bits. In NTP Version 3 secondary
   161     * servers, this is the 32-bit IPv4 address of the reference source. In NTP
   162     * Version 4 secondary servers, this is the low order 32 bits of the latest
   163     * transmit timestamp of the reference source. NTP primary (stratum 1)
   164     * servers should set this field to a code identifying the external
   165     * reference source according to the following list. If the external
   166     * reference is one of those listed, the associated code should be used.
   167     * Codes for sources not listed can be contrived as appropriate.
   168     * 
   169     * Code     External Reference Source
   170     * ----     -------------------------
   171     * LOCL     uncalibrated local clock used as a primary reference for
   172     *          a subnet without external means of synchronization
   173     * PPS      atomic clock or other pulse-per-second source
   174     *          individually calibrated to national standards
   175     * ACTS     NIST dialup modem service
   176     * USNO     USNO modem service
   177     * PTB      PTB (Germany) modem service
   178     * TDF      Allouis (France) Radio 164 kHz
   179     * DCF      Mainflingen (Germany) Radio 77.5 kHz
   180     * MSF      Rugby (UK) Radio 60 kHz
   181     * WWV      Ft. Collins (US) Radio 2.5, 5, 10, 15, 20 MHz
   182     * WWVB     Boulder (US) Radio 60 kHz
   183     * WWVH     Kaui Hawaii (US) Radio 2.5, 5, 10, 15 MHz
   184     * CHU      Ottawa (Canada) Radio 3330, 7335, 14670 kHz
   185     * LORC     LORAN-C radionavigation system
   186     * OMEG     OMEGA radionavigation system
   187     * GPS      Global Positioning Service
   188     * GOES     Geostationary Orbit Environment Satellite
   189     */
       public byte[] referenceIdentifier = {0, 0, 0, 0};


       /**
   194     * This is the time at which the local clock was last set or corrected, in
   195     * seconds since 00:00 1-Jan-1900.
   196     */
       public double referenceTimestamp = 0;


       /**
   201     * This is the time at which the request departed the client for the
   202     * server, in seconds since 00:00 1-Jan-1900.
   203     */
       public double originateTimestamp = 0;


       /**
   208     * This is the time at which the request arrived at the server, in seconds
   209     * since 00:00 1-Jan-1900.
   210     */
       public double receiveTimestamp = 0;


       /**
   215     * This is the time at which the reply departed the server for the client,
   216     * in seconds since 00:00 1-Jan-1900.
   217     */
       public double transmitTimestamp = 0;


       /**
   222     * Constructs a new NtpMessage from an array of bytes.
   223     */
       public NtpMessage(byte[] array) {
          // See the packet format diagram in RFC 2030 for details 
          leapIndicator = (byte) ((array[0] >> 6) & 0x3);
          version = (byte) ((array[0] >> 3) & 0x7);
          mode = (byte) (array[0] & 0x7);
          stratum = unsignedByteToShort(array[1]);
          pollInterval = array[2];
          precision = array[3];

          rootDelay = (array[4] * 256.0) + 
             unsignedByteToShort(array[5]) +
             (unsignedByteToShort(array[6]) / 256.0) +
             (unsignedByteToShort(array[7]) / 65536.0);

          rootDispersion = (unsignedByteToShort(array[8]) * 256.0) + 
             unsignedByteToShort(array[9]) +
             (unsignedByteToShort(array[10]) / 256.0) +
             (unsignedByteToShort(array[11]) / 65536.0);

         referenceIdentifier[0] = array[12];
          referenceIdentifier[1] = array[13];
          referenceIdentifier[2] = array[14];
          referenceIdentifier[3] = array[15];

          referenceTimestamp = decodeTimestamp(array, 16);
          originateTimestamp = decodeTimestamp(array, 24);
          receiveTimestamp = decodeTimestamp(array, 32);
          transmitTimestamp = decodeTimestamp(array, 40);
       }


       private static final long DAYS = 25567; // 1 Jan 1900 to 1 Jan 1970
       private static final long SECS = 60 * 60 * 24 * DAYS;

       // Translate Java/Unix's epoch (1 Jan 1970) to NTP's epoch
       // (1 Jan 1900) and convert from milliseconds to fractions of seconds
       public static final double now () {
          return System.currentTimeMillis()/1000.0 + SECS;
       }



       /**
   267     * Constructs a new NtpMessage in client -> server mode, and sets the
   268     * transmit timestamp to the current time.
   269     */
       public NtpMessage() {
          // Note that all the other member variables are already set with
          // appropriate default values.
          this.mode = 3;
          this.transmitTimestamp = now();
       }



       /**
   280     * This method constructs the data bytes of a raw NTP packet.
   281     */
       public byte[] toByteArray() {
          // All bytes are automatically set to 0
          byte[] p = new byte[48];

          p[0] = (byte) (leapIndicator << 6 | version << 3 | mode);
          p[1] = (byte) stratum;
          p[2] = (byte) pollInterval;
          p[3] = (byte) precision;

          // root delay is a signed 16.16-bit FP, in Java an int is 32-bits
          int l = (int) (rootDelay * 65536.0);
          p[4] = (byte) ((l >> 24) & 0xFF);
          p[5] = (byte) ((l >> 16) & 0xFF);
          p[6] = (byte) ((l >> 8) & 0xFF);
          p[7] = (byte) (l & 0xFF);

          // root dispersion is an unsigned 16.16-bit FP, in Java there are no
          // unsigned primitive types, so we use a long which is 64-bits 
          long ul = (long) (rootDispersion * 65536.0);
          p[8] = (byte) ((ul >> 24) & 0xFF);
          p[9] = (byte) ((ul >> 16) & 0xFF);
          p[10] = (byte) ((ul >> 8) & 0xFF);
          p[11] = (byte) (ul & 0xFF);

          p[12] = referenceIdentifier[0];
          p[13] = referenceIdentifier[1];
          p[14] = referenceIdentifier[2];
          p[15] = referenceIdentifier[3];

          encodeTimestamp(p, 16, referenceTimestamp);
          encodeTimestamp(p, 24, originateTimestamp);
          encodeTimestamp(p, 32, receiveTimestamp);
          encodeTimestamp(p, 40, transmitTimestamp);

          return p; 
       }


       /**
        * Returns a string representation of a NtpMessage
        */
       public String toString() {
          String precisionStr =
             new DecimalFormat("0.#E0").format(Math.pow(2, precision));

          return "Leap indicator: " + leapIndicator + "\n" +
             "Version: " + version + "\n" +
             "Mode: " + mode + "\n" +
             "Stratum: " + stratum + "\n" +
             "Poll: " + pollInterval + "\n" +
             "Precision: " + precision + " (" + precisionStr + " seconds)\n" + 
             "Root delay: " + new DecimalFormat("0.00").format(rootDelay*1000) + " ms\n" +
             "Root dispersion: " + new DecimalFormat("0.00").format(rootDispersion*1000) + " ms\n" + 
             "Reference identifier: " + referenceIdentifierToString(referenceIdentifier, stratum, version) + "\n" +
             "Reference timestamp: " + timestampToString(referenceTimestamp) + "\n" +
             "Originate timestamp: " + timestampToString(originateTimestamp) + "\n" +
             "Receive timestamp:   " + timestampToString(receiveTimestamp) + "\n" +
             "Transmit timestamp:  " + timestampToString(transmitTimestamp);
       }



       /**
        * Converts an unsigned byte to a short.  By default, Java assumes that
        * a byte is signed.
        */
       public static short unsignedByteToShort(byte b)
       {
          if((b & 0x80)==0x80) return (short) (128 + (b & 0x7f));
          else return (short) b;
       }



       /**
        * Will read 8 bytes of a message beginning at <code>pointer</code>
        * and return it as a double, according to the NTP 64-bit timestamp
        * format.
        */
       public static double decodeTimestamp(byte[] array, int pointer)
       {
          double r = 0.0;

          for(int i=0; i<8; i++)
             {
                r += unsignedByteToShort(array[pointer+i]) * Math.pow(2, (3-i)*8);
             }

          return r;
       }



       /**
       * Encodes a timestamp in the specified position in the message
        */
       public static void encodeTimestamp (final byte[] array, final int pointer, double timestamp)    {
          // Converts a double into a 64-bit fixed point
          for(int i=0; i<8; i++) {
             // 2^24, 2^16, 2^8, .. 2^-32
             final double base = Math.pow(2, (3-i)*8);
             // Capture byte value
             array[pointer+i] = (byte) (timestamp / base);

             // Subtract captured value from remaining total
             timestamp = timestamp - (double) (unsignedByteToShort(array[pointer+i]) * base);
          }

          // From RFC 2030: It is advisable to fill the non-significant
          // low order bits of the timestamp with a random, unbiased
          // bitstring, both to avoid systematic roundoff errors and as
          // a means of loop detection and replay detection.
          array[7] = (byte) (Math.random()*255.0);
       }



       /**
        * Returns a timestamp (number of seconds since 00:00 1-Jan-1900) as a
        * formatted date/time string. 
        */
       private static String dtf = "%1$ta, %1$td %1$tb %1$tY, %1$tI:%1$tm:%1$tS.%1$tL %1$tp %1$tZ";

       public static String timestampToString (final double timestamp) {
          if(timestamp==0) return "0";
          // timestamp is relative to 1900, utc is used by Java and is relative to 1970 
          return millisToString(Math.round (1000.0*(timestamp-SECS)));
       }

       public static Date timestampToDate(final double timestamp) {
          if(timestamp==0) return millisToDate(0);
          // timestamp is relative to 1900, utc is used by Java and is relative to 1970 
          return millisToDate(Math.round (1000.0*(timestamp-SECS)));
       }

       public static String millisToString(final long ms) {
           String abc = "";
           abc = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss:SSS").format(new java.util.Date(ms));
   //      System.out.println("millisToDate = "+new SimpleDateFormat("dd/MM/yyyy HH:mm:ss:SSS").format(new java.util.Date(ms)) );
   //       return String.format (dtf, ms);
           return abc;
       }

       public static Date millisToDate (final long ms) {
           return new java.util.Date(ms);
       }


       /**
        * Returns a string representation of a reference identifier according
        * to the rules set out in RFC 2030.
        */
        public static String referenceIdentifierToString(byte[] ref, short stratum, byte version) {
            // From the RFC 2030:
            // In the case of NTP Version 3 or Version 4 stratum-0 (unspecified)
            // or stratum-1 (primary) servers, this is a four-character ASCII
            // string, left justified and zero padded to 32 bits.
            if (stratum == 0 || stratum == 1) {
                return new String(ref);
            } // In NTP Version 3 secondary servers, this is the 32-bit IPv4
            // address of the reference source.
            else if (version == 3) {
                return unsignedByteToShort(ref[0]) + "."
                        + unsignedByteToShort(ref[1]) + "."
                        + unsignedByteToShort(ref[2]) + "."
                        + unsignedByteToShort(ref[3]);
            } // In NTP Version 4 secondary servers, this is the low order 32 bits
            // of the latest transmit timestamp of the reference source.
            else if (version == 4) {
                return "" + ((unsignedByteToShort(ref[0]) / 256.0)
                        + (unsignedByteToShort(ref[1]) / 65536.0)
                        + (unsignedByteToShort(ref[2]) / 16777216.0)
                        + (unsignedByteToShort(ref[3]) / 4294967296.0));
            }
            return "";
        }
        
   }
    //</editor-fold>
    
    //<editor-fold defaultstate="collapsed" desc="System">
    /** jmeno operacniho systemu na kterem JVM zrovna bezi */
    private final static String osName = System.getProperty("os.name","");
    
    private static boolean isWindows(){
        return osName.toLowerCase().startsWith("windows");
    }
    
    private static boolean isLinux(){
        return osName.toLowerCase().startsWith("linux");
    }
    
    public static void sleep(long ms) {
        if (ms>0) try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) { }
    }
    //</editor-fold>
    
}
