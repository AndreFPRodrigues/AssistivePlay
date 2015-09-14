package tbb.touch;

public class IOEvent {

	int type;
	int timestamp;
	int code;
	int value;
	
	public IOEvent(int type, int code, int value, int timestamp){
		this.type=type;
		this.code=code;
		this.value=value;
		this.timestamp=timestamp;
	}
	
	public int getType() {
		return type;
	}

	public int getTimestamp() {
		return timestamp;
	}

	public int getCode() {
		return code;
	}

	public int getValue() {
		return value;
	}
}
