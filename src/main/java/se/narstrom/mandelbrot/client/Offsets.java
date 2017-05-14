package se.narstrom.mandelbrot.client;

class Offsets {
	int top;
	int left;

	Offsets(int top, int left) {
		this.top = top;
		this.left = left;
	}

	@Override
	public String toString() {
		return "(top: " + top + ", left: " + left + ")";
	}
}
