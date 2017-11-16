package transport;

import com.ibm.saguaro.system.Radio;

public class Frame {
	protected byte type = Radio.FCF_DATA;
	private byte seqno = 0;
	private static byte seqnoCounter = 0;
	private int panId;
	private int destAddr;
	private boolean hasDestAddr;
	private int srcAddr;
	private boolean hasSrcAddr;
	private byte[] payload;
	
	public Frame() {
		seqno = seqnoCounter++;
	}
	
	public boolean isBeacon() {
		return type == Radio.FCF_BEACON;
	}
	
	public boolean isData() {
		return type == Radio.FCF_DATA;
	}
	
	public boolean isAck() {
		return type == Radio.FCF_ACK;
	}

	public byte getSeqno() {
		return seqno;
	}
	
	public void setSeqno(byte seqno) {
		this.seqno = seqno;
	}

	public int getPanId() {
		return panId;
	}
	
	public void setPanId(int panId) {
		this.panId = panId;
	}
	
	public int getSrcAddr() {
		return srcAddr;
	}
	
	public void setSrcAddr(int srcAddr) {
		this.srcAddr = srcAddr;
		hasSrcAddr = true;
	}
	
	public boolean isSrcAddr() {
		return hasSrcAddr;
	}

	public int getDestAddr() {
		return destAddr;
	}

	public void setDestAddr(int destAddr) {
		this.destAddr = destAddr;
		hasDestAddr = true;
	}
	
	public boolean isDestAddr() {
		return hasDestAddr;
	}

	public byte[] getPayload() {
		return payload;
	}

	public void setPayload(byte[] payload) {
		this.payload = payload;
	}
}
