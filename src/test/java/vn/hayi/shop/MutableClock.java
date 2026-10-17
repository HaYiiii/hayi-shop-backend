package vn.hayi.shop;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** UTC clock that tests can move forward; {@link #reset()} returns it to real time. */
public final class MutableClock extends Clock {

	private volatile Duration offset = Duration.ZERO;

	public void advance(Duration duration) {
		this.offset = this.offset.plus(duration);
	}

	public void reset() {
		this.offset = Duration.ZERO;
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Instant instant() {
		return Instant.now().plus(this.offset);
	}

}
