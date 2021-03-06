/*
 * Copyright 2015 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.cli.net;

import static org.onosproject.net.DeviceId.deviceId;

import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import org.apache.karaf.shell.commands.Argument;
import org.apache.karaf.shell.commands.Command;
import org.apache.karaf.shell.commands.Option;
import org.onlab.packet.MplsLabel;
import org.onlab.packet.VlanId;
import org.onosproject.cli.AbstractShellCommand;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.TributarySlot;
import org.onosproject.net.newresource.ContinuousResource;
import org.onosproject.net.newresource.Resource;
import org.onosproject.net.newresource.ResourceService;

import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.DiscreteDomain;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;
import org.onosproject.net.newresource.Resources;

/**
 * Lists available resources.
 */
@Command(scope = "onos", name = "resources",
         description = "Lists available resources")
public class ResourcesCommand extends AbstractShellCommand {

    @Option(name = "-s", aliases = "--sort", description = "Sort output",
            required = false, multiValued = false)
    boolean sort = false;

    @Option(name = "-t", aliases = "--typeStrings", description = "List of resource types to be printed",
            required = false, multiValued = true)
    String[] typeStrings = null;

    Set<String> typesToPrint;

    @Argument(index = 0, name = "deviceIdString", description = "Device ID",
              required = false, multiValued = false)
    String deviceIdStr = null;

    @Argument(index = 1, name = "portNumberString", description = "PortNumber",
              required = false, multiValued = false)
    String portNumberStr = null;


    private ResourceService resourceService;

    @Override
    protected void execute() {
        resourceService = get(ResourceService.class);

        if (typeStrings != null) {
            typesToPrint = new HashSet<>(Arrays.asList(typeStrings));
        } else {
            typesToPrint = Collections.emptySet();
        }

        if (deviceIdStr != null && portNumberStr != null) {
            DeviceId deviceId = deviceId(deviceIdStr);
            PortNumber portNumber = PortNumber.fromString(portNumberStr);

            printResource(Resources.discrete(deviceId, portNumber).resource(), 0);
        } else if (deviceIdStr != null) {
            DeviceId deviceId = deviceId(deviceIdStr);

            printResource(Resources.discrete(deviceId).resource(), 0);
        } else {
            printResource(Resource.ROOT, 0);
        }
    }

    private void printResource(Resource resource, int level) {
        // TODO add an option to show only available resource
        Set<Resource> children = resourceService.getRegisteredResources(resource);

        if (resource.equals(Resource.ROOT)) {
            print("ROOT");
        } else {
            String resourceName = resource.last().getClass().getSimpleName();

            if (children.isEmpty() && !typesToPrint.isEmpty() && !typesToPrint.contains(resourceName)) {
                // This resource is target of filtering
                return;
            }


            if (resource instanceof ContinuousResource) {
                String s = ((String) resource.last());
                String simpleName = s.substring(s.lastIndexOf('.') + 1);
                print("%s%s: %f", Strings.repeat(" ", level),
                                  simpleName,
                                  // Note: last() does not return, what we've registered
                                  // following does not work
                                  //((Class<?>) resource.last()).getSimpleName(),
                                  ((ContinuousResource) resource).value());
                // Continuous resource is terminal node, stop here
                return;
            } else {

                String toString = String.valueOf(resource.last());
                if (toString.startsWith(resourceName)) {
                    print("%s%s", Strings.repeat(" ", level),
                          toString);
                } else {
                    print("%s%s: %s", Strings.repeat(" ", level),
                          resourceName,
                          toString);
                }
            }
        }


        // Classify children into aggregatable terminal resources and everything else

        Set<Class<?>> aggregatableTypes = ImmutableSet.<Class<?>>builder()
                .add(VlanId.class)
                .add(MplsLabel.class)
                .build();
        // (last() resource name) -> { Resource }
        Multimap<String, Resource> aggregatables = ArrayListMultimap.create();
        List<Resource> nonAggregatable = new ArrayList<>();

        for (Resource r : children) {
            if (r instanceof ContinuousResource) {
                // non-aggregatable terminal node
                nonAggregatable.add(r);
            } else if (aggregatableTypes.contains(r.last().getClass())) {
                // aggregatable & terminal node
                String className = r.last().getClass().getSimpleName();
                if (typesToPrint.isEmpty() || typesToPrint.contains(className)) {
                    aggregatables.put(className, r);
                }
            } else {
                nonAggregatable.add(r);
            }
        }

        // print aggregated (terminal)
        aggregatables.asMap().entrySet()
            .forEach(e -> {
                // for each type...
                String resourceName = e.getKey();

                RangeSet<Long> rangeSet = TreeRangeSet.create();

                // aggregate into RangeSet
                e.getValue().stream()
                    .map(Resource::last)
                    .map(res -> {
                            if (res instanceof VlanId) {
                                return (long) ((VlanId) res).toShort();
                            } else if (res instanceof MplsLabel) {
                                return (long) ((MplsLabel) res).toInt();
                            } else if (res instanceof TributarySlot) {
                                return ((TributarySlot) res).index();
                            }
                            // TODO support Lambda (OchSignal types)
                            return 0L;
                        })
                    .map(Range::singleton)
                    .map(range -> range.canonical(DiscreteDomain.longs()))
                    .forEach(rangeSet::add);

                print("%s%s: %s", Strings.repeat(" ", level + 1),
                      resourceName,
                      rangeSet);
            });


        // print non-aggregatables (recurse)
        if (sort) {
            nonAggregatable.stream()
                    .sorted((o1, o2) -> String.valueOf(o1.id()).compareTo(String.valueOf(o2.id())))
                    .forEach(r -> printResource(r, level + 1));
        } else {
            nonAggregatable.forEach(r -> printResource(r, level + 1));
        }
    }
}
