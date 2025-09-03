package com.ra4king.circuitsim.gui;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CircuitSimVersion implements Comparable<CircuitSimVersion> {
	private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)(b?)");
	
	public static final CircuitSimVersion VERSION = new CircuitSimVersion("1.10.0-CE");

	private final String version;
	private final int major;
	private final int minor;
	private final int bugfix;
	private final boolean beta;
	
	public CircuitSimVersion(String version) {
		this.version = version;
		
		Matcher matcher = VERSION_PATTERN.matcher(version);
		
		if (!matcher.find()) {
			throw new IllegalArgumentException("Invalid version string: " + version);
		}
		
		major = Integer.parseInt(matcher.group(1));
		minor = Integer.parseInt(matcher.group(2));
		bugfix = Integer.parseInt(matcher.group(3));
		beta = !matcher.group(4).isEmpty();
	}
	
	public String getVersion() {
		return version;
	}
	
	@Override
	public int compareTo(CircuitSimVersion o) {
		if (this.major > o.major) {
			return 1;
		} else if (this.major == o.major) {
			if (this.minor > o.minor) {
				return 1;
			} else if (this.minor == o.minor) {
				if (this.bugfix > o.bugfix) {
					return 1;
				} else if (this.bugfix == o.bugfix) {
					if (this.beta == o.beta) {
						return 0;
					} else if (o.beta) {
						return 1;
					}
				}
			}
		}
		
		return -1;
	}
}
