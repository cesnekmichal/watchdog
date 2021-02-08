import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 *
 * @author Česnek Michal, UNIDATAZ s.r.o.
 */
public class NTPUtil {
    
    public static void main(String[] args) {
        ScheduledExecutorService pool = Executors.newScheduledThreadPool ( 1 );
        Runnable r = new Runnable() {
            Long ntp = null;
            long noTime = 0;
            @Override
            public void run() {
                if(ntp==null){
                    ntp = getNTPDateTimeOffsetMilis();
                    if(ntp!=null){
                        setSystemDateAndTime(ntp);
                        System.out.println("NTP diff: "+ntp);
                        for (long mins = noTime; mins >= 0; mins--) {
                            writeTime(System.currentTimeMillis()-(mins*60_000));
                        }
                    } else {
                        noTime++;
                    }
                } else {
                    writeTime(System.currentTimeMillis());
                }
            }
        };
        try {
            pool.scheduleAtFixedRate(r, 0L, 1L, TimeUnit.MINUTES);
            while (!pool.awaitTermination(1L, TimeUnit.SECONDS)) {
                sleep(1000);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
    
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private static final void writeTime(long currentTimeMilis){
        System.out.println(sdf.format(new Date(currentTimeMilis)));
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
            SimpleDateFormat dateTimeFormatter = new SimpleDateFormat("MMddhhmm[[yy]yy]");
            Date dateTime = new Date(System.currentTimeMillis()+dateTimeOffsetMilis);
            String[] cmds = new String[]{"cmd","/C","date",dateTimeFormatter.format(dateTime)};
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
                String sout = IS.toString(p.getInputStream());
                String serr = IS.toString(p.getErrorStream());
                if(serr!=null && !serr.isEmpty()) System.err.println(serr);
                return sout+serr;
            } catch (IOException ex) {
                ex.printStackTrace();
                return null;
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
          if(stratum==0 || stratum==1) {
             return new String(ref);
          }

          // In NTP Version 3 secondary servers, this is the 32-bit IPv4
          // address of the reference source.
          else if(version==3) {
                return unsignedByteToShort(ref[0]) + "." +
                   unsignedByteToShort(ref[1]) + "." +
                   unsignedByteToShort(ref[2]) + "." +
                   unsignedByteToShort(ref[3]);
             }

          // In NTP Version 4 secondary servers, this is the low order 32 bits
          // of the latest transmit timestamp of the reference source.
          else if(version==4) {
                return "" + ((unsignedByteToShort(ref[0]) / 256.0) + 
                   (unsignedByteToShort(ref[1]) / 65536.0) +
                   (unsignedByteToShort(ref[2]) / 16777216.0) +
                   (unsignedByteToShort(ref[3]) / 4294967296.0));
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
