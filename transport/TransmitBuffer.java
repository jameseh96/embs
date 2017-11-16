package transport;

import com.ibm.saguaro.logger.Logger;
import com.ibm.saguaro.system.LED;
import com.ibm.saguaro.system.Mote;
import com.ibm.saguaro.system.Time;
import com.ibm.saguaro.system.Timer;
import com.ibm.saguaro.system.TimerEvent;
import com.ibm.saguaro.system.csr;

public class TransmitBuffer {
	private static Timer timer = new Timer();
	private static FrameHolder[] buffer = new FrameHolder[5];
	private static long nextWake = Time.currentTime(Time.MILLISECS) + 0x7FFFFF;
	private static final long minSleep = Time.toTickSpan(Time.MILLISECS, 10);
	
	static {
		timer.setCallback(new TimerEvent(null) {
			public void invoke(byte param, long time) {
				TransmitBuffer.onWake(param, time);
			}
		});
		setTimer();
	}

	public static void addFrame(DataFrame df) {
		resetWakeTime();
		FrameHolder f = new FrameHolder(df);
		for (int i = 0; i < 5; i++) {
			if (buffer[i] == null) {
				buffer[i] = f;
				updateWakeTime(f);
				break;
			}
		}
		setTimer();
	}
	
	public static void onAck(byte seqno) {
		resetWakeTime();
		for (int i = 0; i < 5; i++) {
			if (buffer[i] != null) {
				FrameHolder f = buffer[i];
				if (f.isAckedBy(seqno)) {
					buffer[i] = null;
					break;
				} else {
					updateWakeTime(f);
				}
			}
		}
		setTimer();
	}
	
	public static void onWake(byte param, long time) {
		long currTime = Time.currentTime(Time.MILLISECS); 
		for (int i = 0; i < 5; i++) {
			if (buffer[i] != null) {
				FrameHolder f = buffer[i];
				if (!f.checkResend(currTime)) {
					buffer[i] = null;
				} else {
					updateWakeTime(f);
				}
			}
		}

		setTimer();
	}
	
	private static void resetWakeTime() {
		nextWake = Time.currentTime(Time.MILLISECS) + 0x7FFFFF;
	}
	
	private static void updateWakeTime(FrameHolder df) {
		long wakeTime = df.getResendTime();
		if (wakeTime < nextWake) {
			nextWake = wakeTime;
		}
	}
	
	private static void setTimer() {
		timer.cancelAlarm();
		long span = Time.toTickSpan(Time.MILLISECS, nextWake - Time.currentTime(Time.MILLISECS));
		if (span < minSleep) {
			span = minSleep;
		}

		timer.setAlarmBySpan(span);
	}

	public static class FrameHolder {
		private long sendTime;
		private long delay = 100; // ms
		private long resendTime;
		private DataFrame frame;
		public FrameHolder(DataFrame df) {
			frame = df;
			sendTime = Time.currentTime(Time.MILLISECS);
			resendTime = sendTime + delay;
		}
		
		public void resend() {
			TXRX.resendDataFrame(frame);
			resendTime = Time.currentTime(Time.MILLISECS) + delay;
			delay *= 2;
		}
		
		public boolean checkResend(long time) {
			if (resendTime <= time) {
				resend();
			}
			return true;
		}
		
		private long getResendTime() {
			return resendTime;
		}
		
		public boolean isAckedBy(byte seqno) {
			return frame.getSeqno() == seqno;
		}
	}
}
