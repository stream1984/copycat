/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package io.atomix.copycat.server.storage.compaction;

import io.atomix.copycat.server.storage.Segment;
import io.atomix.copycat.server.storage.SegmentManager;
import io.atomix.copycat.server.storage.Storage;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds tasks for the {@link Compaction#MINOR} compaction process.
 * <p>
 * Minor compaction is the more frequent and lightweight process. Periodically, according to the configured
 * {@link Storage#minorCompactionInterval()}, a background thread will evaluate the log for minor compaction. The
 * minor compaction process iterates through segments and selects compactable segments based on the ratio of entries
 * that have been {@link io.atomix.copycat.server.storage.Log#clean(long) cleaned}. Minor compaction is generational. The
 * {@link io.atomix.copycat.server.storage.compaction.MinorCompactionManager} is more likely to select recently written
 * segments than older segments. Once a set of segments have been compacted, for each segment a
 * {@link io.atomix.copycat.server.storage.compaction.MinorCompactionTask} rewrites the segment without cleaned entries.
 * This rewriting results in a segment with missing entries, and Copycat's Raft implementation accounts for that.
 * For instance, a segment with entries {@code {1, 2, 3}} can become {@code {1, 3}} after being cleaned, and any attempt
 * to {@link io.atomix.copycat.server.storage.Log#get(long) read} entry {@code 2} will result in a {@code null} entry.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
public class MinorCompactionManager implements CompactionManager {

  @Override
  public List<CompactionTask> buildTasks(Storage storage, SegmentManager segments) {
    List<List<Segment>> groups = getCleanableGroups(storage, segments);
    List<CompactionTask> tasks = new ArrayList<>(groups.size());
    for (List<Segment> group : groups) {
      tasks.add(new MinorCompactionTask(segments, group));
    }
    return tasks;
  }

  /**
   * Returns a list of segment sets to clean.
   *
   * @return A list of segment sets to clean in the order in which they should be cleaned.
   */
  private List<List<Segment>> getCleanableGroups(Storage storage, SegmentManager manager) {
    List<List<Segment>> clean = new ArrayList<>();
    List<Segment> segments = null;
    Segment previousSegment = null;
    for (Segment segment : getCleanableSegments(storage, manager)) {
      if (segments == null) {
        segments = new ArrayList<>();
        segments.add(segment);
      }
      // If the previous segment is not an instance of the same version as this segment then reset the segments list.
      // Similarly, if the previous segment doesn't directly end with the index prior to the first index in this segment then
      // reset the segments list. We can only combine segments that are direct neighbors of one another.
      else if (previousSegment != null && (previousSegment.descriptor().version() != segment.descriptor().version() || previousSegment.lastIndex() != segment.firstIndex() - 1)) {
        clean.add(segments);
        segments = new ArrayList<>();
        segments.add(segment);
      }
      // If the total count of entries in all segments is less then the total slots in any individual segment, combine the segments.
      else if (segments.stream().mapToLong(Segment::count).sum() + segment.count() < segments.stream().mapToLong(Segment::length).max().getAsLong()) {
        segments.add(segment);
      }
      // If there's not enough room to combine segments, reset the segments list.
      else {
        clean.add(segments);
        segments = new ArrayList<>();
        segments.add(segment);
      }
      previousSegment = segment;
    }

    // Ensure all cleanable segments have been added to the clean segments list.
    if (segments != null) {
      clean.add(segments);
    }
    return clean;
  }

  /**
   * Returns a list of compactable segments.
   *
   * @return A list of compactable segments.
   */
  private Iterable<Segment> getCleanableSegments(Storage storage, SegmentManager manager) {
    List<Segment> segments = new ArrayList<>();
    for (Segment segment : manager.segments()) {
      // Only allow compaction of segments that are full.
      if (segment.isCompacted() || (segment.isFull() && segment.lastIndex() <= manager.commitIndex())) {

        // If the segment is small enough that it can be combined with another segment then add it.
        if (segment.count() < segment.length() / 2) {
          segments.add(segment);
        } else {
          // Calculate the percentage of entries that have been marked for cleaning in the segment.
          double cleanPercentage = segment.cleanCount() / (double) segment.count();

          // If the percentage of entries marked for cleaning times the segment version meets the cleaning threshold,
          // add the segment to the segments list for cleaning.
          if (cleanPercentage * segment.descriptor().version() >= storage.compactionThreshold()) {
            segments.add(segment);
          }
        }
      }
    }
    return segments;
  }

}