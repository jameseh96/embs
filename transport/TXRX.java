package transport;

import com.ibm.saguaro.logger.*;
import com.ibm.saguaro.system.*;

public class TXRX {
//	private static Timer tsend;
//	private static long xmitDelay;
	private static byte[] packet;
	static Radio radio = new Radio();
	static byte[] EUI = new byte[8];
	static int shortAddr;
	static int panId = 0xAA;
	
	static OnReceiveCallback[] onReceiveCallbacks = new OnReceiveCallback[10];
	static int callbackCount = 0;

	static {
		// Open the default radio
		radio.open(Radio.DID, null, 0, 0);
		Assembly.setSystemInfoCallback(new SystemInfo(null) {
			public int invoke(int type, int info) {
				return onDelete(type, info);
			}
		});

		Mote.getParam(Mote.EUI64, EUI, 0);
		shortAddr = Util.get16le(EUI, 0);
		Logger.appendInt(shortAddr);
		Logger.flush(Mote.INFO);

		// Set the PAN ID to panId and the short address to 0xEE
		radio.setPanId(panId, true);
		radio.setShortAddr(shortAddr);
		// Prepare beacon frame with source addressing
		packet = new byte[127];

		// Put radio into receive mode for a long time on channel 1
		radio.setChannel((byte) 3);
		radio.setRxHandler(new DevCallback(null) {
			public int invoke(int flags, byte[] data, int len, int info, long time) {
				return TXRX.onReceive(flags, data, len, info, time);
			}
		});
		radio.setTxHandler(new DevCallback(null) {
			public int invoke(int flags, byte[] data, int len, int info, long time) {
				return TXRX.onTransmit(flags, data, len, info, time);
			}
		});
		radio.startRx(Device.ASAP, 0, Time.currentTicks() + 0x7FFFFFFF);

//		tsend = new Timer();
//		tsend.setCallback(new TimerEvent(null) {
//			public void invoke(byte param, long time) {
//				TXRX.testPing(param, time);
//			}
//		});
//		// Convert the periodic delay from ms to platform ticks
//		xmitDelay = Time.toTickSpan(Time.MILLISECS, 2000);
//		// Start the timer
//		tsend.setAlarmBySpan(xmitDelay);
	}

	public static int getShortAddr() {
		return shortAddr;
	}

	public static int getPanId() {
		return panId;
	}
	
	public static void addOnReceiveCallback(OnReceiveCallback cb) {
		onReceiveCallbacks[callbackCount++] = cb;
	}

	public static void sendDataFrame(DataFrame df) {
		if (df.getDestAddr() != Radio.SADDR_BROADCAST) {
			TransmitBuffer.addFrame(df);
		}
		resendDataFrame(df);
	}
	
	public static void resendDataFrame(DataFrame df) {
		int length = formatDataFrame(df);

		radio.transmit(Device.ASAP | Radio.TXMODE_CCA, packet, 0, length, 0);
	}

	// On a received frame toggle LED
	private static int onReceive(int flags, byte[] data, int len, int info, long time) {
		if (data == null) { // marks end of reception period
			// re-enable reception for a very long time
			radio.startRx(Device.ASAP, 0, Time.currentTicks() + 0x7FFFFFFF);
			return 0;
		}
		LED.setState((byte) 1, (byte) (~LED.getState((byte) 1) & 1));
		
		DataFrame frame = parseDataFrame(data, len);
		
		for (int i = 0; i < callbackCount; i++) {
			onReceiveCallbacks[i].invoke(frame);
		}
		return 0;
	}

	private static int onTransmit(int flags, byte[] data, int len, int info, long time) {
		if ((flags & Device.FLAG_FAILED) == 0) {
			TransmitBuffer.onAck(data[2]);
		}
		return 0;
	}

	private static DataFrame parseDataFrame(byte[] rpacket, int len) {
		return new DataFrame(rpacket, len);
	}

	private static int formatFrame(Frame frame) {
		packet[2] = frame.getSeqno();
		int index = 3;
		if (frame.isDestAddr()) {
			Util.set16le(packet, index, frame.getPanId());
			index += 2;
			Util.set16le(packet, index, frame.getDestAddr());
			index += 2;
		}
		if (frame.isSrcAddr()) {
			Util.set16le(packet, index, frame.getSrcAddr());
			index += 2;
		}
		return index;
	}

	private static int formatDataFrame(DataFrame df) {
		packet[0] = Radio.FCF_DATA | Radio.FCF_NSPID | Radio.FCF_ACKRQ;
		packet[1] = (byte) ((df.isSrcAddr() ? Radio.FCA_SRC_SADDR : 0) | (df.isDestAddr() ? Radio.FCA_DST_SADDR : 0));

		int index = formatFrame(df);
		byte[] payload = df.getPayload();
		Util.copyData(payload, 0, packet, index, payload.length);
		return index + payload.length;
	}

//	public static void testPing(byte param, long time) {
//		DataFrame frame = new DataFrame();
//		frame.setPanId(panId);
//		frame.setDestAddr(Radio.SADDR_BROADCAST);
//		frame.setPayload(new byte[] { 0x01, 0x02, 0x03 });
//		sendDataFrame(frame);
//		tsend.setAlarmBySpan(xmitDelay);
//	}

	// delegate to handle assembly delete, releases the radio
	private static int onDelete(int type, int info) {
		if (type == Assembly.SYSEV_DELETED) {
			radio.stopRx();
			radio.close();
		}
		return 0;
	}

	public interface OnReceiveCallback {
		public void invoke(DataFrame df);
	}
}