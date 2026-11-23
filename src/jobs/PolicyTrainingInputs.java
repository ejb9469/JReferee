package jobs;

import java.util.List;
import java.util.Map;

record PolicyTrainingInputs(List<Map<String, Object>> datasets, Map<String, Long> roots) { }