import java.io.*;
import java.net.*;
import java.util.*;
import java.nio.ByteBuffer;

public class TCPend {
  public static void main(String[] args) {
    boolean isSender = false;
    int mtu = -1;
    int sws = -1;
    int port = -1;
    int remotePort = -1;
    String remoteIP = null;
    String fileName = null;

    for (int i = 0; i < args.length; i++) {
      if (args[i].equals("-p")) {
        port = Integer.parseInt(args[++i]);
      } else if (args[i].equals("-s")) {
        remoteIP = args[++i];
        isSender = true;
      } else if (args[i].equals("-a")) {
        remotePort = Integer.parseInt(args[++i]);
      } else if (args[i].equals("-f")) {
        fileName = args[++i];
      } else if (args[i].equals("-m")) {
        mtu = Integer.parseInt(args[++i]);
      } else if (args[i].equals("-c")) {
        sws = Integer.parseInt(args[++i]);
      }
    }

    if (isSender) {
      TCPSender sender = new TCPSender(port, remoteIP, remotePort, fileName, mtu, sws);
      sender.start();
    } else {
      TCPReceiver receiver = new TCPReceiver(port, fileName, mtu, sws);
      receiver.start();
    }
  }
}

class TCPSegment {
  public static final int HEADER_SIZE = 24;
  private boolean synFlag;
  private boolean ackFlag;
  private boolean finFlag;
  private int seqNum;
  private int ackNum;
  private byte[] data;
  private long timestamp;
  private int checksum;
  private int length;

  public TCPSegment(int seqNum, int ackNum, boolean syn, boolean ack, boolean fin, byte[] data, long timestamp) {
    this.seqNum = seqNum;
    this.ackNum = ackNum;
    this.synFlag = syn;
    this.ackFlag = ack;
    this.finFlag = fin;
    this.data = (data != null) ? data : new byte[0];
    this.timestamp = timestamp;
    this.length = this.data.length;
  }

  public TCPSegment() {
  }

  public boolean isSynFlag() {
    return synFlag;
  }

  public boolean isAckFlag() {
    return ackFlag;
  }

  public boolean isFinFlag() {
    return finFlag;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public int getSeqNumber() {
    return seqNum;
  }

  public int getAckNumber() {
    return ackNum;
  }

  public int getLength() {
    return length;
  }

  public byte[] getData() {
    return data;
  }

  public byte[] serialize() {
    ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + length);
    buffer.putInt(seqNum);
    buffer.putInt(ackNum);
    buffer.putLong(timestamp);

    byte[] packet = buffer.array();

    int lengthFlags = (length << 3);
    if (synFlag)
      lengthFlags |= 0x4;
    if (ackFlag)
      lengthFlags |= 0x2;
    if (finFlag)
      lengthFlags |= 0x1;

    buffer.putInt(lengthFlags);
    buffer.putInt(0);

    if (data.length > 0 && data != null) {
      buffer.put(data);
    }

    checksum = computeChecksum(packet);
    ByteBuffer.wrap(packet).putInt(20, checksum);
    return packet;
  }

  public static TCPSegment deserialize(byte[] packet) {
    TCPSegment segment = new TCPSegment();
    ByteBuffer buffer = ByteBuffer.wrap(packet);

    segment.seqNum = buffer.getInt();
    segment.ackNum = buffer.getInt();
    segment.timestamp = buffer.getLong();

    int lengthFlags = buffer.getInt();

    segment.length = lengthFlags >>> 3;
    segment.synFlag = (lengthFlags & 0x4) != 0;
    segment.ackFlag = (lengthFlags & 0x2) != 0;
    segment.finFlag = (lengthFlags & 0x1) != 0;

    int receivedChecksum = buffer.getInt();

    byte[] packetCopy = packet.clone();
    ByteBuffer.wrap(packetCopy).putInt(20, 0);

    int computedChecksum = computeChecksum(packetCopy);
    if (computedChecksum != receivedChecksum) {
      return null;
    }

    if (segment.length <= 0) {
      segment.data = new byte[0];
    } else {
      segment.data = new byte[segment.length];
      buffer.get(segment.data);
    }

    return segment;
  }

  private static int computeChecksum(byte[] data) {
    int sum = 0;
    for (int i = 0; i < data.length - 1; i += 2) {
      sum += ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
    }
    if (data.length % 2 != 0) {
      sum += (data[data.length - 1] & 0xFF) << 8;
    }
    while ((sum >> 16) != 0) {
      sum = (sum & 0xFFFF) + (sum >> 16);
    }
    return ~sum & 0xFFFF;
  }

  public String getFlags() {
    StringBuilder flags = new StringBuilder();
    flags.append(synFlag ? "S" : "-");
    flags.append(" ");
    flags.append(ackFlag ? "A" : "-");
    flags.append(" ");
    flags.append(finFlag ? "F" : "-");
    flags.append(" ");
    flags.append((data != null && data.length > 0) ? "D" : "-");
    return flags.toString();
  }
}

class TCPSender {
  private Map<Integer, Long> sentTimes = new HashMap<>();
  private Map<Integer, TCPSegment> sentSegments = new HashMap<>();
  private Map<Integer, Integer> retransmitCounts = new HashMap<>();

  private int mtu;
  private int sws;
  private int remotePort;
  private String fileName;
  private DatagramSocket socket;
  private InetAddress remoteAddress;

  private long startTime = 0;
  private double estimatedRTT = 0;
  private double devRTT = 0;
  private int seqNum = 0;
  private int ackNum = 0;
  private long timeoutInterval = 5000000000L;
  private int lastAckReceived = 0;
  private int duplicateAckCount = 0;
  private int totalDataSent = 0;
  private int packetsSent = 0;
  private int packetsReceived = 0;
  private int totalRetransmissions = 0;
  private int duplicateAcks = 0;

  private static final int MAXIMUM_RETRANSMISSIONS = 16;

  public TCPSender(int port, String remoteIP, int remotePort, String fileName, int mtu, int sws) {
    try {
      this.socket = new DatagramSocket(port);
      this.remoteAddress = InetAddress.getByName(remoteIP);
      this.socket.setSoTimeout(100);
      this.remotePort = remotePort;
      this.fileName = fileName;
      this.mtu = mtu;
      this.sws = sws;
      this.startTime = System.nanoTime();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public void start() {
    if (!performThreeWayHandshake()) {
      System.out.println("Three-way handshake failed. Exiting.");
      return;
    }

    sendFile();

    TCPSegment finSegment = new TCPSegment(seqNum, ackNum, false, false, true, new byte[0], System.nanoTime());
    sendSegment(finSegment);
    log(finSegment, "snd");

    TCPSegment finAckSegment = receiveSegmentWithRetry(finSegment);
    if (finAckSegment != null) {
      log(finAckSegment, "rcv");
      ackNum = finAckSegment.getSeqNumber() + 1;
      seqNum = finAckSegment.getAckNumber();

      TCPSegment finalAckSegment = new TCPSegment(seqNum, ackNum, false, true, false, new byte[0], System.nanoTime());
      sendSegment(finalAckSegment);
      log(finalAckSegment, "snd");
    }

    System.out.println("Amount of Data transferred: " + totalDataSent + " bytes");
    System.out.println("Number of packets sent: " + packetsSent);
    System.out.println("Number of packets received: " + packetsReceived);
    System.out.println("Number of retransmissions: " + totalRetransmissions);
    System.out.println("Number of duplicate acknowledgments: " + duplicateAcks);

    socket.close();
  }

  public void sendFile() {
    try {
      List<TCPSegment> window = new ArrayList<>();
      int offset = 0;
      int dataLength = mtu - TCPSegment.HEADER_SIZE;

      FileInputStream fis = new FileInputStream(fileName);
      byte[] fileData = fis.readAllBytes();
      fis.close();

      while (!window.isEmpty() || offset < fileData.length) {
        while (window.size() < sws && offset < fileData.length) {
          int chunkSize = Math.min(fileData.length - offset, dataLength);
          byte[] chunk = Arrays.copyOfRange(fileData, offset, offset + chunkSize);

          TCPSegment dataSegment = new TCPSegment(seqNum, ackNum, false, true, false, chunk, System.nanoTime());
          sendSegment(dataSegment);
          log(dataSegment, "snd");

          sentSegments.put(seqNum, dataSegment);
          sentTimes.put(seqNum, System.nanoTime());
          retransmitCounts.put(seqNum, 0);
          window.add(dataSegment);

          totalDataSent += chunkSize;
          seqNum += chunkSize;
          offset += chunkSize;
        }

        try {
          TCPSegment ackSegment = receiveSegment();
          if (ackSegment != null) {
            log(ackSegment, "rcv");
            handleAck(ackSegment, window);
          }
        } catch (Exception e) {
          timeoutCheck(window);
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public void timeoutCheck(List<TCPSegment> window) {
    long currentTime = System.nanoTime();
    for (TCPSegment segment : new ArrayList<>(window)) {
      int seqNum = segment.getSeqNumber();
      Long sentTime = sentTimes.get(seqNum);
      if (sentTime != null && currentTime - sentTime >= timeoutInterval) {
        retransmitSegment(segment);
      }
    }
  }

  private boolean performThreeWayHandshake() {
    TCPSegment synSegment = new TCPSegment(seqNum, 0, true, false, false, new byte[0], System.nanoTime());
    sendSegment(synSegment);
    log(synSegment, "snd");

    TCPSegment synAckSegment = receiveSegmentWithRetry(synSegment);
    if (synAckSegment == null || !synAckSegment.isSynFlag() || !synAckSegment.isAckFlag()) {
      return false;
    }

    log(synAckSegment, "rcv");

    ackNum = synAckSegment.getSeqNumber() + 1;
    seqNum = synAckSegment.getAckNumber();
    updateTimeoutInterval(synAckSegment.getTimestamp());

    TCPSegment ackSegment = new TCPSegment(seqNum, ackNum, false, true, false, new byte[0], System.nanoTime());
    sendSegment(ackSegment);
    log(ackSegment, "snd");

    return true;
  }

  private void handleAck(TCPSegment ackSegment, List<TCPSegment> window) {
    int ack = ackSegment.getAckNumber();

    if (ack == lastAckReceived) {
      duplicateAcks++;
      duplicateAckCount++;

      if (!window.isEmpty() && duplicateAckCount >= 3) {
        TCPSegment segmentToRetransmit = window.get(0);
        retransmitSegment(segmentToRetransmit);
        duplicateAckCount = 0;
      }
    } else {
      lastAckReceived = ack;
      duplicateAckCount = 0;
      updateTimeoutInterval(ackSegment.getTimestamp());

      window.removeIf(seg -> seg.getSeqNumber() < ack);
      sentSegments.keySet().removeIf(seq -> seq < ack);
      sentTimes.keySet().removeIf(seq -> seq < ack);
      retransmitCounts.keySet().removeIf(seq -> seq < ack);
    }
  }

  private void retransmitSegment(TCPSegment segment) {
    int seqNum = segment.getSeqNumber();
    int count = retransmitCounts.getOrDefault(seqNum, 0);

    if (count >= MAXIMUM_RETRANSMISSIONS) {
      System.out.println("Maximum retransmissions are reached for segment " + seqNum + ". Terminating connection.");
      socket.close();
      System.exit(1);
    }

    TCPSegment retransmitSegment = new TCPSegment(
        segment.getSeqNumber(),
        segment.getAckNumber(),
        segment.isSynFlag(),
        segment.isAckFlag(),
        segment.isFinFlag(),
        segment.getData(),
        System.nanoTime());

    sendSegment(retransmitSegment);
    log(retransmitSegment, "snd");
    sentTimes.put(seqNum, System.nanoTime());
    retransmitCounts.put(seqNum, count + 1);
    totalRetransmissions++;
  }

  private void sendSegment(TCPSegment segment) {
    try {
      byte[] data = segment.serialize();
      DatagramPacket packet = new DatagramPacket(data, data.length, remoteAddress, remotePort);
      socket.send(packet);
      packetsSent++;
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private TCPSegment receiveSegment() throws IOException {
    byte[] buffer = new byte[mtu];
    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
    socket.receive(packet);
    TCPSegment segment = TCPSegment.deserialize(Arrays.copyOf(packet.getData(), packet.getLength()));
    if (segment != null) {
      packetsReceived++;
    }
    return segment;
  }

  private TCPSegment receiveSegmentWithRetry(TCPSegment toRetransmit) {
    int attempts = 0;
    while (attempts < MAXIMUM_RETRANSMISSIONS) {
      try {
        socket.setSoTimeout(5000);
        TCPSegment segment = receiveSegment();
        if (segment != null) {
          return segment;
        }
      } catch (SocketTimeoutException e) {
        sendSegment(toRetransmit);
        log(toRetransmit, "snd");
        totalRetransmissions++;
        attempts++;
      } catch (IOException e) {
        e.printStackTrace();
        return null;
      }
    }
    return null;
  }

  private void log(TCPSegment segment, String type) {
    double elapsedTime = (System.nanoTime() - startTime) / 1_000_000.0;
    System.out.printf("%s %.2f %s %d %d %d%n", type, elapsedTime, segment.getFlags(),
        segment.getSeqNumber(), segment.getLength(), segment.getAckNumber());
  }

  private void updateTimeoutInterval(long segmentTimestamp) {
    long rtt = System.nanoTime() - segmentTimestamp;

    if (estimatedRTT != 0) {
      double sampleRTT = rtt;
      double sampleDev = Math.abs(sampleRTT - estimatedRTT);
      devRTT = 0.75 * devRTT + 0.25 * sampleDev;
      estimatedRTT = 0.875 * estimatedRTT + 0.125 * sampleRTT;
      timeoutInterval = (long) (estimatedRTT + 4 * devRTT);
    } else {
      devRTT = 0;
      estimatedRTT = rtt;
      timeoutInterval = (long) (2 * estimatedRTT);
    }
  }
}

class TCPReceiver {
  private int mtu;
  private int sws;
  private DatagramSocket socket;
  private String fileName;

  private long startTime = 0;
  private int seqNumber = 0;
  private int expectedSeqNum = 1;

  private int totalDataReceived = 0;
  private int packetsSent = 0;
  private int packetsReceived = 0;
  private int outOfSequencePackets = 0;
  private int checksumErrors = 0;

  private InetAddress senderAddr;
  private int senderPort;
  private FileOutputStream fos;
  private Map<Integer, byte[]> receiveBuffer = new TreeMap<>();

  public TCPReceiver(int port, String fileName, int mtu, int sws) {
    try {
      this.socket = new DatagramSocket(port);
      this.fileName = fileName;
      this.mtu = mtu;
      this.sws = sws;
      this.startTime = System.nanoTime();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public void start() {
    try {
      if (!performThreeWayHandshake()) {
        System.out.println("Three-way handshake failed. Exiting.");
        return;
      }

      fos = new FileOutputStream(fileName);
      receiveFile();
      fos.close();

      System.out.println("Amount of Data received: " + totalDataReceived + " bytes");
      System.out.println("Number of packets sent: " + packetsSent);
      System.out.println("Number of packets received: " + packetsReceived);
      System.out.println("Number of out-of-sequence packets discarded: " + outOfSequencePackets);
      System.out.println("Number of packets discarded due to incorrect checksum: " + checksumErrors);

      socket.close();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private void receiveFile() {
    try {
      boolean connectionClosed = false;

      while (!connectionClosed) {
        byte[] buffer = new byte[mtu];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        try {
          socket.receive(packet);
          TCPSegment segment = TCPSegment.deserialize(Arrays.copyOf(packet.getData(), packet.getLength()));

          if (segment == null) {
            checksumErrors++;
            continue;
          }

          packetsReceived++;
          log(segment, "rcv");

          if (segment.isFinFlag()) {
            connectionClosed = true;
            handleFinFlag(segment);
          } else if (segment.getLength() > 0) {
            handleSegment(segment);
          }
        } catch (SocketTimeoutException e) {
          // Ignore timeout exceptions
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private boolean performThreeWayHandshake() {
    try {
      byte[] buffer = new byte[mtu];
      DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
      socket.receive(packet);

      senderPort = packet.getPort();
      senderAddr = packet.getAddress();

      TCPSegment synSegment = TCPSegment.deserialize(Arrays.copyOf(packet.getData(), packet.getLength()));
      if (synSegment == null) {
        checksumErrors++;
        return false;
      }

      packetsReceived++;
      log(synSegment, "rcv");

      if (!synSegment.isSynFlag()) {
        return false;
      }

      expectedSeqNum = synSegment.getSeqNumber() + 1;

      TCPSegment synAckSegment = new TCPSegment(seqNumber, expectedSeqNum, true, true, false, new byte[0],
          synSegment.getTimestamp());
      sendSegment(synAckSegment);
      log(synAckSegment, "snd");

      seqNumber++;

      packet = new DatagramPacket(buffer, buffer.length);
      socket.receive(packet);
      TCPSegment ackSegment = TCPSegment.deserialize(Arrays.copyOf(packet.getData(), packet.getLength()));
      if (ackSegment == null) {
        checksumErrors++;
        return false;
      }

      packetsReceived++;
      log(ackSegment, "rcv");

      return ackSegment.isAckFlag();
    } catch (Exception e) {
      e.printStackTrace();
      return false;
    }
  }

  private void handleSegment(TCPSegment segment) {
    try {
      int seq = segment.getSeqNumber();

      if (seq > expectedSeqNum) {
        receiveBuffer.put(seq, segment.getData());
        outOfSequencePackets++;

        TCPSegment dupAck = new TCPSegment(seqNumber, expectedSeqNum, false, true, false, new byte[0],
            segment.getTimestamp());
        sendSegment(dupAck);
        log(dupAck, "snd");
      } else if (seq == expectedSeqNum) {
        dataWrite(segment.getData());
        expectedSeqNum += segment.getLength();
        totalDataReceived += segment.getLength();

        while (receiveBuffer.containsKey(expectedSeqNum)) {
          byte[] data = receiveBuffer.remove(expectedSeqNum);
          dataWrite(data);
          expectedSeqNum += data.length;
          totalDataReceived += data.length;
        }

        TCPSegment ackSegment = new TCPSegment(seqNumber, expectedSeqNum, false, true, false, new byte[0],
            segment.getTimestamp());
        sendSegment(ackSegment);
        log(ackSegment, "snd");
      } else {
        TCPSegment dupAck = new TCPSegment(seqNumber, expectedSeqNum, false, true, false, new byte[0],
            segment.getTimestamp());
        sendSegment(dupAck);
        log(dupAck, "snd");
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private void sendSegment(TCPSegment segment) {
    try {
      byte[] data = segment.serialize();
      DatagramPacket packet = new DatagramPacket(data, data.length, senderAddr, senderPort);
      socket.send(packet);
      packetsSent++;
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void dataWrite(byte[] data) {
    try {
      fos.write(data);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void handleFinFlag(TCPSegment finSegment) {
    try {
      byte[] buffer = new byte[mtu];
      TCPSegment finAckSegment = new TCPSegment(seqNumber, finSegment.getSeqNumber() + 1, false, true, true,
          new byte[0],
          finSegment.getTimestamp());
      sendSegment(finAckSegment);
      log(finAckSegment, "snd");

      seqNumber++;
      DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
      socket.setSoTimeout(5000);

      try {
        socket.receive(packet);
        TCPSegment ackSegment = TCPSegment.deserialize(Arrays.copyOf(packet.getData(), packet.getLength()));
        if (ackSegment != null) {
          log(ackSegment, "rcv");
          packetsReceived++;
        }
      } catch (SocketTimeoutException e) {
        // Ignore timeout exceptions
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private void log(TCPSegment segment, String type) {
    double elapsedTime = (System.nanoTime() - startTime) / 1000000.0;
    System.out.printf("%s %.2f %s %d %d %d%n",
        type, elapsedTime, segment.getFlags(),
        segment.getSeqNumber(), segment.getLength(), segment.getAckNumber());
  }
}

