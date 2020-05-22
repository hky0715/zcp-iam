package com.skcc.cloudz.zcp.iam.common.model;

import java.util.HashMap;
import java.util.Map;

public enum PodStatus {
	// this is a phase of pod's status
	Pending, Running, Succeeded, Failed, Unknown, Terminating, Completed;
	
	private static final Map<String, PodStatus> lookup = new HashMap<String, PodStatus>();
	
	static {
	    for (PodStatus status : PodStatus.values()) {
	        lookup.put(status.name(), status);
	    }
	}
	
	public static PodStatus findByPhase(String phase) {
	    return lookup.get(phase);
	}
}
