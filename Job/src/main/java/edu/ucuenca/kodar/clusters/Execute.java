/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.ucuenca.kodar.clusters;

import org.apache.log4j.PropertyConfigurator;

/**
 * Executes the clustering Job.
 *
 * @author Xavier Sumba <xavier.sumba93@ucuenca.ec>
 */
public class Execute {

    public static void main(String[] args) throws Exception {
        PropertyConfigurator.configure("log4j.properties");
        if (args.length >= 1) {
            Clustering c = new Clustering(args[0]);
            c.run(16);
        } else {
            throw new Exception("ERROR: Invalid number of arguments");
        }
    }

}
