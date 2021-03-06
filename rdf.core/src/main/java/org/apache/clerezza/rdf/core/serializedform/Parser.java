/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.clerezza.rdf.core.serializedform;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.logging.Level;

import org.apache.clerezza.rdf.core.Graph;
import org.apache.clerezza.rdf.core.MGraph;
import org.apache.clerezza.rdf.core.UriRef;
import org.apache.clerezza.rdf.core.impl.SimpleMGraph;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This singleton class provides a method
 * <code>parse</code> to transform serialized RDF forms into {@link Graph}s.
 *
 * Functionality is delegated to registered {@link ParsingProvider}s. Such
 * <code>ParsingProvider</code>s can be registered and unregistered, later
 * registered
 * <code>ParsingProvider</code>s shadow previously registered providers for the
 * same format.
 *
 * Note on synchronization:
 * <code>ParsingProvider</code>s must be able to handle concurrent requests.
 *
 * @author reto
 *
 */
@Component(service = Parser.class)
public class Parser {

    private ConfigurationAdmin configurationAdmin;
    /**
     * The list of providers in the order of registration
     */
    private List<ParsingProvider> providerList = new ArrayList<ParsingProvider>();
    /**
     * A map to quickly locate a provider
     */
    private volatile Map<String, ParsingProvider> providerMap = new HashMap<String, ParsingProvider>();
    /**
     * The singleton instance
     */
    private volatile static Parser instance;
    private boolean active;

    private static final Logger log = LoggerFactory.getLogger(Parser.class);
    /**
     * the constructor sets the singleton instance to allow instantiation
     * by OSGi-DS. This constructor should not be called except by OSGi-DS,
     * otherwise the static <code>getInstance</code> method should be used.
     */
    public Parser() {
        log.info("constructing Parser");
        Parser.instance = this;
    }

    /**
     * A constructor for tests, which doesn't set the singleton instance
     *
     * @param dummy an ignored argument to distinguish this from the other constructor
     */
    Parser(Object dummy) {
        active = true;
    }

    /**
     * This returns the singleton instance, if an instance has been previously
     * created (e.g. by OSGi declarative services) this instance is returned,
     * otherwise a new instance is created and providers are injected using 
     * the service provider interface (META-INF/services/)
     *
     * @return the singleton Parser instance
     */
    public static Parser getInstance() {
        if (instance == null) {
            synchronized (Parser.class) {
                if (instance == null) {
                    new Parser();
                    Iterator<ParsingProvider> parsingProviders =
                            ServiceLoader.load(ParsingProvider.class).iterator();
                    while (parsingProviders.hasNext()) {
                        ParsingProvider parsingProvider = parsingProviders.next();
                        instance.bindParsingProvider(parsingProvider);
                    }
                    instance.active = true;
                    instance.refreshProviderMap();
                }
            }
        }
        return instance;

    }

    @Activate
    protected void activate(final ComponentContext componentContext) {
        active = true;
        refreshProviderMap();
        //changing the congiguration before this finshed activating causes a new instance to be created
        /*(new Thread() {
            @Override
            public void run() {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ex) {
                    return;
                }
                refreshProviderMap();
            }
            
            
        }).start();*/
    }

    @Deactivate
    protected void deactivate(final ComponentContext componentContext) {
        active = false;
    }
    
    @Modified
    void modified(ComponentContext ctx) {
        log.debug("modified");
    }

    /**
     * Parses a serialized Graph from an InputStream. This delegates the
     * processing to the provider registered for the specified format, if
     * the formatIdentifier contains a ';'-character only the section before
     * that character is used for choosing the provider.
     *
     * @param serializedGraph an inputstream with the serialization
     * @param formatIdentifier a string identifying the format (usually the MIME-type)
     * @return the graph read from the stream
     * @throws UnsupportedFormatException
     */
    public Graph parse(InputStream serializedGraph,
            String formatIdentifier) throws UnsupportedFormatException {
        return parse(serializedGraph, formatIdentifier, null);
    }

    /**
     * Parses a serialized Graph from an InputStream. This delegates the
     * processing to the provider registered for the specified format, if
     * the formatIdentifier contains a ';'-character only the section before
     * that character is used for choosing the provider.
     *
     * @param target the MGraph to which the parsed triples are added
     * @param serializedGraph an inputstream with the serialization
     * @param formatIdentifier a string identifying the format (usually the MIME-type)
     * @throws UnsupportedFormatException
     */
    public void parse(MGraph target, InputStream serializedGraph,
            String formatIdentifier) throws UnsupportedFormatException {
        parse(target, serializedGraph, formatIdentifier, null);
    }

    /**
     * Parses a serialized Graph from an InputStream. This delegates the
     * processing to the provider registered for the specified format, if
     * the formatIdentifier contains a ';'-character only the section before
     * that character is used for choosing the provider.
     *
     * @param serializedGraph an inputstream with the serialization
     * @param formatIdentifier a string identifying the format (usually the MIME-type)
     * @param baseUri the uri against which relative uri-refs are evaluated
     * @return the graph read from the stream
     * @throws UnsupportedFormatException
     */
    public Graph parse(InputStream serializedGraph,
            String formatIdentifier, UriRef baseUri) throws UnsupportedFormatException {
        MGraph mGraph = new SimpleMGraph();
        parse(mGraph, serializedGraph, formatIdentifier, baseUri);
        return mGraph.getGraph();
    }

    /**
     * Parses a serialized Graph from an InputStream. This delegates the
     * processing to the provider registered for the specified format, if
     * the formatIdentifier contains a ';'-character only the section before
     * that character is used for choosing the provider.
     *
     * @param target the MGraph to which the parsed triples are added
     * @param serializedGraph an inputstream with the serialization
     * @param formatIdentifier a string identifying the format (usually the MIME-type)
     * @param baseUri the uri against which relative uri-refs are evaluated
     * @throws UnsupportedFormatException
     */
    public void parse(MGraph target, InputStream serializedGraph,
            String formatIdentifier, UriRef baseUri) throws UnsupportedFormatException {
        String deParameterizedIdentifier;
        int semicolonPos = formatIdentifier.indexOf(';');
        if (semicolonPos > -1) {
            deParameterizedIdentifier = formatIdentifier.substring(0, semicolonPos);
        } else {
            deParameterizedIdentifier = formatIdentifier;
        }
        ParsingProvider provider = providerMap.get(deParameterizedIdentifier);
        if (provider == null) {
            throw new UnsupportedParsingFormatException(formatIdentifier);
        }
        provider.parse(target, serializedGraph, formatIdentifier, baseUri);
    }

    /**
     * Get a set of supported formats
     *
     * @return a set if stings identifying formats (usually the MIME-type)
     */
    public Set<String> getSupportedFormats() {
        return Collections.unmodifiableSet(providerMap.keySet());
    }

    /**
     * Registers a parsing provider
     *
     * @param provider the provider to be registered
     */
    @Reference(policy = ReferencePolicy.DYNAMIC,
            cardinality = ReferenceCardinality.MULTIPLE)
    public void bindParsingProvider(ParsingProvider provider) {
        providerList.add(provider);
        refreshProviderMap();
    }

    /**
     * Unregister a parsing provider
     *
     * @param provider the provider to be deregistered
     */
    public void unbindParsingProvider(ParsingProvider provider) {
        providerList.remove(provider);
        refreshProviderMap();
    }

    /**
     * Update providerMap with the providers in the providerList
     *
     */
    private void refreshProviderMap() {
        if (active) {
            try {
                final Map<String, ParsingProvider> newProviderMap = new HashMap<String, ParsingProvider>();
                for (ParsingProvider provider : providerList) {
                    String[] formatIdentifiers = getFormatIdentifiers(provider);
                    for (String formatIdentifier : formatIdentifiers) {
                        newProviderMap.put(formatIdentifier, provider);
                    }
                }
                providerMap = newProviderMap;
                if (configurationAdmin != null) { //i.e. when we are in an OSGi environment
                    Dictionary<String, Object> newConfig = configurationAdmin.getConfiguration(getClass().getName()).getProperties();
                    if (newConfig == null) {
                        newConfig = new Hashtable<String, Object>();
                    }
                    Set<String> supportedFormats = getSupportedFormats();
                    String[] supportedFromatsArray = supportedFormats.toArray(new String[supportedFormats.size()]);
                    newConfig.put(SupportedFormat.supportedFormat, supportedFromatsArray);
                    configurationAdmin.getConfiguration(getClass().getName()).update(newConfig);
                }
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    

    /**
     * Extract format identifiers for a parsing provider
     *
     * @param provider the provider to be registered
     * @return formatIdentifiers
     */
    private String[] getFormatIdentifiers(ParsingProvider parsingProvider) {
        Class<? extends ParsingProvider> clazz = parsingProvider.getClass();
        SupportedFormat supportedFormatAnnotation = clazz.getAnnotation(SupportedFormat.class);
        String[] formatIdentifiers = supportedFormatAnnotation.value();
        return formatIdentifiers;
    }

    @Reference
    protected void bindConfigurationAdmin(ConfigurationAdmin configurationAdmin) {
        this.configurationAdmin = configurationAdmin;
    }

    protected void unbindConfigurationAdmin(ConfigurationAdmin configurationAdmin) {
        this.configurationAdmin = null;
    }
}
