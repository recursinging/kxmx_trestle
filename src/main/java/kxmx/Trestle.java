
package kxmx;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fazecast.jSerialComm.SerialPort;

import kxmx.CommandLine.Option;

/**
 * 
 * 
 * @author recursinging
 * 
 */
public class Trestle implements Runnable {

	@Option(names = { "-v", "--verbose" }, description = "Verbose mode. Helpful for troubleshooting. "
			+ "Multiple -v options increase the verbosity.")
	private boolean[] verbose = new boolean[0];

	@Option(names = { "-h", "--help" }, usageHelp = true, description = "Displays this help message and quits.")
	private boolean helpRequested = false;

	@Option(names = { "-i", "--receive-host" }, description = "The OSC UDP Receive Host (default: ${DEFAULT-VALUE})")
	private String udpReceiveHost = "0.0.0.0";

	@Option(names = { "-t", "--target-host" }, description = "The OSC UDP Target Host (default: ${DEFAULT-VALUE})")
	private String udpTargetHost = "0.0.0.0";

	@Option(names = { "-r", "--receive-port" }, description = "The OSC UDP Receive Port (default: ${DEFAULT-VALUE})")
	private int udpReceivePort = 8000;

	@Option(names = { "-s", "--target-port" }, description = "The OSC UDP Target Port (default: ${DEFAULT-VALUE})")
	private int udpTargetPort = 9000;

	@Option(names = { "-d", "--serial-device" }, description = "The connected serial device name (Optional)")
	private String serialDevice = null;

	/**
	 * SLIP Serial delimiter bytes
	 */
	private final byte END = (byte) 0xc0;
	private final byte ESC = (byte) 0xdb;
	private final byte ESC_END = (byte) 0xdc;
	private final byte ESC_ESC = (byte) 0xdd;

	private SerialPort serial;
	private DatagramSocket socket;
	private ExecutorService executor = Executors.newCachedThreadPool();

	private boolean serialPortConnected = false;
	private long lastStatCheck = System.currentTimeMillis();
	private long messagesSent = 0;
	private long bytesSent = 0;
	private long messagesReceived = 0;
	private long bytesReceived = 0;

	private SerialPort locateDevice() {
		if (serialDevice == null) {
			System.out.println("Looking for a known KXMX device...");
			for (SerialPort port : SerialPort.getCommPorts()) {
				String name = port.getDescriptivePortName();
				if (name.contains("Teensy") || name.contains("Arduino")) {
					System.out.println("Found one at: " + port.getSystemPortName() + " (" + name + ")");
					return port;
				}
			}
		} else {
			System.out.println("Looking for device " + serialDevice);
			for (SerialPort port : SerialPort.getCommPorts()) {
				if (port.getSystemPortName().equalsIgnoreCase(serialDevice)) {
					System.out
							.println("Found " + port.getSystemPortName() + " (" + port.getDescriptivePortName() + ")");
					return port;
				}
			}

		}
		System.out.println("Nothing found yet. Trying again...");
		return null;
	}

	@Override
	public void run() {
		try {
			System.out.println("Welcome to Trestle.\n\n");
			System.out.print("Setting up a (multicast capable) socket at " + udpReceiveHost + ":" + udpReceivePort + "...");
			SocketAddress receiveSocket = new InetSocketAddress(InetAddress.getByName(udpReceiveHost), udpReceivePort);
			socket = new MulticastSocket(receiveSocket);
			System.out.println("OK!\n OSC Messages sent to this socket will be proxied to the serial device.\n");

			System.out.print("Setting up the target UDP address...");
			SocketAddress targetSocket = new InetSocketAddress(InetAddress.getByName(udpTargetHost), udpTargetPort);
			System.out.println(
					"OK!\n OSC Messages from the serial device be proxied to " + udpTargetHost + ":" + udpTargetPort + "\n");

			System.out.println("Setting up the serial device...");
			while (true) {
				if (!serialPortConnected) {
					serial = locateDevice();
					if (serial == null) {
						Thread.sleep(1000);
						continue;
					}

					if (!serial.openPort()) {
						serial.closePort();
						System.err.println("Unable to open: " + serial.getSystemPortName());
						continue;
					}

					serialPortConnected = true;
					System.out.println("OK! Starting the proxy.");

					executor.execute(() -> {
						try {
							while (true) {
								if (!serialPortConnected) {
									throw new Exception("Serial device is not connected!");
								}
								while (serial.bytesAvailable() <= 0) {
									Thread.sleep(1);
								}

								byte[] serialBuffer = new byte[serial.bytesAvailable()];
								byte[] dgramBuffer = new byte[serialBuffer.length];
								int numBytesRead = serial.readBytes(serialBuffer, serialBuffer.length);

								int cnt = 0;
								boolean esc = false;
								for (int i = 0; i < numBytesRead; ++i) {

									byte val = serialBuffer[i];

									if (esc) {
										if (val == ESC_END) {
											dgramBuffer[cnt++] = END;
										} else if (val == ESC_ESC) {
											dgramBuffer[cnt++] = ESC;
										} else {
											dgramBuffer[cnt++] = val;
										}
										esc = false;
										continue;
									}

									if (val == ESC) {
										esc = true;
									} else {
										esc = false;
										if (val == END) {
											if (cnt > 0) {
												DatagramPacket dgram = new DatagramPacket(dgramBuffer, cnt,
														targetSocket);
												socket.send(dgram);
												messagesSent++;
												bytesSent += cnt;
												cnt = 0;
											} else {
												continue;
											}
										} else {
											dgramBuffer[cnt++] = val;
										}
									}
								}
							}
						} catch (Exception e) {
							e.printStackTrace();
						}
						serial.closePort();
						serialPortConnected = false;
						System.out.println("Lost connection to the serial device. Trying to re-locate it...");
					});

					executor.execute(() -> {
						try {
							byte[] serialBuffer = new byte[65535];
							byte[] dgramBuffer = new byte[65535];

							while (true) {
								if (!serialPortConnected) {
									throw new Exception("Serial device is not connected!");
								}
								DatagramPacket p = new DatagramPacket(dgramBuffer, dgramBuffer.length);
								socket.receive(p);
								int cnt = 0;
								serialBuffer[0] = END;
								for (int i = 1; i < p.getLength(); i++) {
									byte val = p.getData()[i];
									if (val == END) {
										serialBuffer[cnt++] = ESC;
										serialBuffer[cnt++] = ESC_END;
									} else if (val == ESC) {
										serialBuffer[cnt++] = ESC;
										serialBuffer[cnt++] = ESC_ESC;
									} else {
										serialBuffer[cnt++] = val;
									}
								}
								serialBuffer[cnt++] = END;
//								byte[] test = new byte[cnt];
//								System.arraycopy(serialBuffer, 0, test, 0, cnt);
//								System.out.println(Arrays.toString(test));
								serial.writeBytes(serialBuffer, cnt);
								messagesReceived++;
								bytesReceived += cnt;
							}
						} catch (Exception e) {
							e.printStackTrace();
						}
						serial.closePort();
						serialPortConnected = false;
						System.out.println("Lost connection to the serial device. Trying to re-locate it...");
					});

					executor.execute(() -> {
						try {
							while (true) {
								if (!serialPortConnected) {
									throw new Exception("Serial device is not connected!");
								}
								if (verbose.length > 0) {
									System.out.println("Sent\t" + messagesSent + "\tmessages (" + bytesSent + " bytes)");
									System.out.println("Received\t" + messagesReceived + "\tmessages (" + bytesReceived + " bytes)");
								}
								Thread.sleep(1000);
							}
						} catch (Exception e) {
							e.printStackTrace();
						}
					});
				}

			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) throws Exception {
		CommandLine.run(new Trestle(), args);
	}

}
