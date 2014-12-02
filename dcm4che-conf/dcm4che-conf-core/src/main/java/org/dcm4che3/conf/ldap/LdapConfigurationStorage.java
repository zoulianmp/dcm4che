/*
 * **** BEGIN LICENSE BLOCK *****
 *  Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 *  The contents of this file are subject to the Mozilla Public License Version
 *  1.1 (the "License"); you may not use this file except in compliance with
 *  the License. You may obtain a copy of the License at
 *  http://www.mozilla.org/MPL/
 *
 *  Software distributed under the License is distributed on an "AS IS" basis,
 *  WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 *  for the specific language governing rights and limitations under the
 *  License.
 *
 *  The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 *  Java(TM), hosted at https://github.com/gunterze/dcm4che.
 *
 *  The Initial Developer of the Original Code is
 *  Agfa Healthcare.
 *  Portions created by the Initial Developer are Copyright (C) 2014
 *  the Initial Developer. All Rights Reserved.
 *
 *  Contributor(s):
 *  See @authors listed below
 *
 *  Alternatively, the contents of this file may be used under the terms of
 *  either the GNU General Public License Version 2 or later (the "GPL"), or
 *  the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 *  in which case the provisions of the GPL or the LGPL are applicable instead
 *  of those above. If you wish to allow use of your version of this file only
 *  under the terms of either the GPL or the LGPL, and not to allow others to
 *  use your version of this file under the terms of the MPL, indicate your
 *  decision by deleting the provisions above and replace them with the notice
 *  and other provisions required by the GPL or the LGPL. If you do not delete
 *  the provisions above, a recipient may use your version of this file under
 *  the terms of any one of the MPL, the GPL or the LGPL.
 *
 *  ***** END LICENSE BLOCK *****
 */
package org.dcm4che3.conf.ldap;

import org.dcm4che3.conf.api.ConfigurationException;
import org.dcm4che3.conf.core.Configuration;
import org.dcm4che3.conf.core.api.LDAP;
import org.dcm4che3.conf.dicom.CommonDicomConfiguration;

import javax.naming.*;
import javax.naming.directory.*;
import javax.naming.ldap.LdapName;
import java.util.*;


public class LdapConfigurationStorage implements Configuration {

    private final String baseDN;
    private final InitialDirContext ldapCtx;
    private final List<Class<?>> allExtensionClasses;

    public List<Class<?>> getAllExtensionClasses() {
        return allExtensionClasses;
    }

    public LdapConfigurationStorage(Hashtable<String, String> env, List<Class<?>> allExtensionClasses) throws ConfigurationException {
        this.allExtensionClasses = allExtensionClasses;

        try {
            env = (Hashtable) env.clone();
            String e = (String) env.get("java.naming.provider.url");
            int end = e.lastIndexOf(47);
            env.put("java.naming.provider.url", e.substring(0, end));
            this.baseDN = e.substring(end + 1);
            this.ldapCtx = new InitialDirContext(env);
        } catch (Exception e) {
            throw new ConfigurationException(e);
        }
    }

    public synchronized void destroySubcontextWithChilds(String name) throws NamingException {
        NamingEnumeration list = getLdapCtx().list(new LdapName(name));

        while (list.hasMore()) {
            this.destroySubcontextWithChilds(((NameClassPair) list.next()).getNameInNamespace());
        }

        getLdapCtx().destroySubcontext(new LdapName(name));
    }


    private void merge(LdapNode ldapNode) {
        try {

            mergeIn(ldapNode);

        } catch (NamingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Map<String, Object> getConfigurationRoot() throws ConfigurationException {
        Object root = getConfigurationNode("/dicomConfigurationRoot", null);
        HashMap<String, Object> map = new HashMap<String, Object>();
        map.put("dicomConfigurationRoot", root);
        return map;
    }

    @Override
    public Object getConfigurationNode(String path, Class configurableClass) throws ConfigurationException {
        // TODO: byte[],x509 to base64
        // special booleanBased EnumSet

        if (path.equals("/dicomConfigurationRoot"))
            configurableClass = CommonDicomConfiguration.DicomConfigurationRootNode.class;

        String dn = LdapConfigUtils.refToLdapDN(path, this);

        try {
            return LdapConfigNodeReader.readNode(this, dn, configurableClass);
        } catch (NamingException e) {
            throw new ConfigurationException("Cannot read node from ldap :" + path, e);
        }
    }

    public void fillExtension(String dn, Map<String, Object> map, String extensionLabel) throws NamingException, ConfigurationException {
        HashMap<String, Object> exts = new HashMap<String, Object>();
        map.put(extensionLabel, exts);

        for (Class<?> aClass : getAllExtensionClasses()) {

            LDAP ldapAnno = aClass.getAnnotation(LDAP.class);

            String subDn;
            if (ldapAnno == null || !ldapAnno.noContainerNode())
                subDn = LdapConfigUtils.dnOf(dn, "cn", aClass.getSimpleName());
            else
                subDn = dn;

            Map ext = (Map) LdapConfigNodeReader.readNode(this, subDn, aClass);
            if (ext == null || ext.isEmpty()) continue;

            exts.put(aClass.getSimpleName(), ext);

        }
    }

    @Override
    public Class getConfigurationNodeClass(String path) throws ConfigurationException, ClassNotFoundException {
        throw new RuntimeException("Not implemented yet");
    }

    @Override
    public boolean nodeExists(String path) throws ConfigurationException {
        throw new RuntimeException("Not implemented yet");
    }

    @Override
    public void persistNode(String path, Map<String, Object> configNode, Class configurableClass) throws ConfigurationException {
        // TODO: byte[], x509 from base64
        // dynamic dn generation for lists... maybe allow to use an extension

        String dn = LdapConfigUtils.refToLdapDN(path, this);

        LdapNode ldapNode = new LdapNode(this);
        ldapNode.setDn(dn);
        ldapNode.populate(configNode, configurableClass);


        merge(ldapNode);

        // TODO: also fill in other parameters from the configNode according to 'partially overwritten' contract
    }


    private void mergeIn(LdapNode ldapNode) throws NamingException {

        // merge attributes of this node
        if (!ldapNode.getObjectClasses().isEmpty()) {

            BasicAttribute objectClass = new BasicAttribute("objectClass");
            for (String c : ldapNode.getObjectClasses()) objectClass.add(c);
            ldapNode.getAttributes().put(objectClass);


            Attributes attributes = null;
            try {
                attributes = ldapCtx.getAttributes(new LdapName(ldapNode.getDn()));
            } catch (NameNotFoundException e) {
                // attributes stay null
            }

            if (attributes == null)
                storeAttributes(ldapNode);
            else {
                // TODO: PERFORMANCE: filter out the attributes that did not change
                // Append objectClass
                ldapNode.getAttributes().remove("objectClass");
                Attribute existingObjectClasses = getLdapCtx().getAttributes(ldapNode.getDn(), new String[]{"objectClass"}).get("objectClass");
                for (String c : ldapNode.getObjectClasses())
                    if (!existingObjectClasses.contains(c))
                        existingObjectClasses.add(c);

                ldapNode.getAttributes().put(existingObjectClasses);

                replaceAttributes(ldapNode);
            }
        }

        // remove children that do not exist in the new config
        // see which objectclasses are children of the node and remove them all
        for (String childObjClass : ldapNode.getChildrenObjectClasses()) {

            NamingEnumeration<SearchResult> ne = LdapConfigUtils.searchSubcontextWithClass(this, childObjClass, ldapNode.getDn());

            while (ne.hasMore()) {
                SearchResult sr = (SearchResult) ne.next();
                // TODO: filter out those who dont need to be killed
                try {
                    destroySubcontextWithChilds(sr.getName());
                } catch (NameNotFoundException exception) {
                    //noop, proceed
                }
            }

        }

        // descent recursively
        for (LdapNode child : ldapNode.getChildren()) mergeIn(child);
    }

    private void storeAttributes(LdapNode ldapNode) throws NamingException {
        getLdapCtx().createSubcontext(new LdapName(ldapNode.getDn()), ldapNode.getAttributes());
    }

    private void replaceAttributes(LdapNode ldapNode) throws NamingException {
        getLdapCtx().modifyAttributes(new LdapName(ldapNode.getDn()), DirContext.REPLACE_ATTRIBUTE, ldapNode.getAttributes());
    }

    @Override
    public void refreshNode(String path) throws ConfigurationException {
        throw new RuntimeException("Not implemented yet");

    }

    @Override
    public void removeNode(String path) throws ConfigurationException {
        LdapConfigUtils.BooleanContainer dnIsKillableWrapper = new LdapConfigUtils.BooleanContainer();
        String dn = LdapConfigUtils.refToLdapDN(path, this, dnIsKillableWrapper);
        if (dnIsKillableWrapper.isKillable()) {
            try {
                destroySubcontextWithChilds(dn);
            } catch (NameNotFoundException nnfe) {
                //noop
            } catch (NamingException e) {
                throw new ConfigurationException(e);
            }
        }

    }

    @Override
    public Iterator search(String liteXPathExpression) throws IllegalArgumentException, ConfigurationException {
        throw new RuntimeException("Not implemented yet");
    }

    public String getBaseDN() {
        return baseDN;
    }

    public InitialDirContext getLdapCtx() {
        return ldapCtx;
    }
}
