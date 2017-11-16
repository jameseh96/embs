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
	static int panId = 0x01;
	
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

		radio.setPanId(panId, true);
		radio.setShortAddr(shortAddr);

		packet = new byte[127];

		// Put radio into receive mode for a long time on channel 1
		radio.setChannel((byte) 1);
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

	private static int onReceive(int flags, byte[] data, int len, int info, long time) {
		if (data == null) { // marks end of reception period
			// re-enable reception for a very long time
			radio.startRx(Device.ASAP, 0, Time.currentTicks() + 0x7FFFFFFF);
			return 0;
		}
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