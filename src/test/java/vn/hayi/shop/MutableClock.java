package vn.hayi.shop;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * UTC clock that tests can move forward or {@link #freeze()}; {@link #reset()} returns it to real time. The bean is
 * shared by every test in the context, so a test that freezes it resets it afterwards.
 */
public final class MutableClock extends Clock {

	private volatile Duration offset = Duration.ZERO;

	private volatile Instant frozenAt;

	public void advance(Duration duration) {
		this.offset = this.offset.plus(duration);
	}

	/**
	 * Stops the clock at the current instant, so test data and the request under test see the same Branch date even
	 * when the run crosses midnight.
	 */
	public void freeze() {
		this.frozenAt = Instant.now();
	}

	public void reset() {
		this.offset = Duration.ZERO;
		this.frozenAt = null;
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
		Instant frozen = this.frozenAt;
		return ((frozen != null) ? frozen : Instant.now()).plus(this.offset);
	}

}
