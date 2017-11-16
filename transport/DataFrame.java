package transport;

import com.ibm.saguaro.system.Radio;
import com.ibm.saguaro.system.Util;

public class DataFrame extends Frame {
	public DataFrame() {
		super();
	}
	
	public DataFrame(byte[] packet, int len) {
		type = (byte)(packet[0] & 0x07);
		setSeqno(packet[2]);

		int index = 3;

		if ((packet[1] & Radio.FCA_DST_SADDR) != 0) {
			setPanId(Util.get16le(packet, index));
			index += 2;
			setDestAddr(Util.get16le(packet, index));
			index += 2;
		}
		;
		if ((packet[1] & Radio.FCA_SRC_SADDR) != 0) {
			if ((packet[0] & Radio.FCF_NSPID) == 0) {
				setPanId(Util.get16le(packet, index)); // overwrite the pan
				index += 2;
			}
			setSrcAddr(Util.get16le(packet, index));
			index += 2;
		}

		byte[] payload = new byte[len - index];
		Util.copyData(packet, index, payload, 0, len - index);
		setPayload(payload);
	}
	
	public DataFrame(DataFrame other) {
		this();
		setPanId(other.getPanId());
		if (other.isDestAddr()) {
			setDestAddr(other.getDestAddr());
		}
		if (other.isSrcAddr()) {
			setSrcAddr(other.getSrcAddr());
		}
		setPayload(other.getPayload());
	}
}
