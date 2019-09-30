/*
 * **** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * J4Care.
 * Portions created by the Initial Developer are Copyright (C) 2015-2019
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * **** END LICENSE BLOCK *****
 *
 */

package org.dcm4chee.arc.ups.impl;

import org.dcm4che3.conf.api.ConfigurationException;
import org.dcm4che3.conf.api.ConfigurationNotFoundException;
import org.dcm4che3.conf.api.IApplicationEntityCache;
import org.dcm4che3.data.*;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.service.DicomServiceException;
import org.dcm4chee.arc.conf.ArchiveAEExtension;
import org.dcm4chee.arc.conf.ArchiveDeviceExtension;
import org.dcm4chee.arc.conf.UPSState;
import org.dcm4chee.arc.entity.GlobalSubscription;
import org.dcm4chee.arc.entity.UPS;
import org.dcm4chee.arc.keycloak.HttpServletRequestInfo;
import org.dcm4chee.arc.query.Query;
import org.dcm4chee.arc.query.QueryContext;
import org.dcm4chee.arc.query.QueryService;
import org.dcm4chee.arc.query.util.QueryParam;
import org.dcm4chee.arc.ups.UPSContext;
import org.dcm4chee.arc.ups.UPSEvent;
import org.dcm4chee.arc.ups.UPSService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Event;
import javax.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 * @since Sep 2019
 */
@ApplicationScoped
public class UPSServiceImpl implements UPSService {

    private static Logger LOG = LoggerFactory.getLogger(UPSServiceImpl.class);
    private static final IOD CREATE_IOD = loadIOD("create-iod.xml");
    private static final IOD SET_IOD = loadIOD("set-iod.xml");

    @Inject
    private UPSServiceEJB ejb;

    @Inject
    private QueryService queryService;

    @Inject
    private Event<List<UPSEvent>> upsEvent;

    @Inject
    private IApplicationEntityCache aeCache;

    @Override
    public UPSContext newUPSContext(Association as) {
        return new UPSContextImpl(as);
    }

    @Override
    public UPSContext newUPSContext(HttpServletRequestInfo httpRequestInfo, ArchiveAEExtension arcAE) {
        return new UPSContextImpl(httpRequestInfo, arcAE);
    }

    @Override
    public UPS createUPS(UPSContext ctx) throws DicomServiceException {
        Attributes attrs = ctx.getAttributes();
        ValidationResult validate = attrs.validate(CREATE_IOD);
        if (!validate.isValid()) {
            throw DicomServiceException.valueOf(validate, attrs);
        }
        if (ctx.isGlobalSubscription()) {
            throw new DicomServiceException(Status.DuplicateSOPinstance,
                    "Cannot create UPS Global Subscription SOP Instance", false);
        }
        if ("SCHEDULED".equals(attrs.getString(Tag.ScheduledProcedureStepStatus))) {
            throw new DicomServiceException(Status.UPSNotScheduled,
                    "The provided value of UPS State was not SCHEDULED");
        }
        if (!attrs.containsValue(Tag.WorklistLabel)) {
            attrs.setString(Tag.WorklistLabel, VR.LO, ctx.getArchiveAEExtension().defaultWorklistLabel());
        }
        try {
            UPS ups = ejb.createUPS(ctx, globalSubscriptions(attrs));
            upsEvent.fire(ctx.getUPSEvents());
            return ups;
        } catch (Exception e) {
            try {
                if (ejb.exists(ctx)) {
                    throw new DicomServiceException(Status.DuplicateSOPinstance,
                            "The UPS already exists.", false);
                }
            } catch (Exception ignore) {}
            throw new DicomServiceException(Status.ProcessingFailure, e);
        }
    }

    @Override
    public UPS updateUPS(UPSContext ctx) throws DicomServiceException {
        Attributes attrs = ctx.getAttributes();
        ValidationResult validate = attrs.validate(SET_IOD);
        if (!validate.isValid()) {
            throw DicomServiceException.valueOf(validate, attrs);
        }
        try {
            UPS ups = ejb.updateUPS(ctx);
            upsEvent.fire(ctx.getUPSEvents());
            return ups;
        } catch (DicomServiceException e) {
            throw e;
        } catch (Exception e) {
             throw new DicomServiceException(Status.ProcessingFailure, e);
        }
    }

    @Override
    public UPS changeUPSState(UPSContext ctx) throws DicomServiceException {
        Attributes attrs = ctx.getAttributes();
        String transactionUID = attrs.getString(Tag.TransactionUID);
        if (transactionUID == null)
            throw new DicomServiceException(Status.InvalidArgumentValue,
                    "The Transaction UID is missing.", false);
        UPSState upsState;
        try {
            upsState = UPSState.fromString(attrs.getString(Tag.ProcedureStepState));
        } catch (NullPointerException e) {
            throw new DicomServiceException(Status.InvalidArgumentValue,
                    "The Procedure Step State is missing.", false);
        } catch (IllegalArgumentException e) {
            throw new DicomServiceException(Status.InvalidArgumentValue,
                    "The Procedure Step State is invalid.", false);
        }
        if (upsState == UPSState.SCHEDULED) {
            throw new DicomServiceException(Status.UPSStateMayNotChangedToScheduled,
                    "The submitted request is inconsistent with the current state of the UPS Instance.", false);
        }
        try {
            UPS ups = ejb.changeUPSState(ctx, upsState, transactionUID);
            upsEvent.fire(ctx.getUPSEvents());
            return ups;
        } catch (DicomServiceException e) {
            throw e;
        } catch (Exception e) {
             throw new DicomServiceException(Status.ProcessingFailure, e);
        }
    }

    @Override
    public UPS requestUPSCancel(UPSContext ctx) throws DicomServiceException {
        try {
            UPS ups = ejb.requestUPSCancel(ctx);
            upsEvent.fire(ctx.getUPSEvents());
            return ups;
        } catch (DicomServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new DicomServiceException(Status.ProcessingFailure, e);
        }
    }

    @Override
    public UPS findUPS(UPSContext ctx) throws DicomServiceException {
        UPS ups = ejb.findUPS(ctx);
        Attributes upsAttrs = ups.getAttributes();
        Attributes patAttrs = ups.getPatient().getAttributes();
        Attributes.unifyCharacterSets(patAttrs, upsAttrs);
        Attributes attrs = new Attributes(patAttrs.size() + upsAttrs.size() + 3);
        attrs.addAll(patAttrs);
        attrs.addAll(upsAttrs);
        attrs.setString(Tag.SOPClassUID, VR.UI, UID.UnifiedProcedureStepPushSOPClass);
        attrs.setString(Tag.SOPInstanceUID, VR.UI, ups.getUpsInstanceUID());
        attrs.setDate(Tag.ScheduledProcedureStepModificationDateTime, VR.DT, ups.getUpdatedTime());
        ctx.setAttributes(attrs);
        return ups;
    }

    @Override
    public void createSubscription(UPSContext ctx) throws DicomServiceException {
        try {
            validateSubscriberAET(ctx);
            switch (ctx.getUpsInstanceUID()) {
                case UID.UPSFilteredGlobalSubscriptionSOPInstance:
                    if (ctx.getAttributes().isEmpty()) {
                        throw new DicomServiceException(Status.InvalidArgumentValue,
                                "Matching Keys are missing.", false);
                    }
                case UID.UPSGlobalSubscriptionSOPInstance:
                    ejb.createOrUpdateGlobalSubscription(ctx, searchNotSubscribedUPS(ctx));
                    break;
                default:
                    ejb.createOrUpdateSubscription(ctx);
            }
            upsEvent.fire(ctx.getUPSEvents());
        } catch (DicomServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new DicomServiceException(Status.ProcessingFailure, e);
        }
    }

    @Override
    public int deleteSubscription(UPSContext ctx) throws DicomServiceException {
        try {
            return ctx.isGlobalSubscription()
                    ? ejb.deleteGlobalSubscription(ctx)
                    : ejb.deleteSubscription(ctx);
        } catch (Exception e) {
            throw new DicomServiceException(Status.ProcessingFailure, e);
        }
    }

    @Override
    public int suspendSubscription(UPSContext ctx) throws DicomServiceException {
        try {
            return ctx.isGlobalSubscription()
                    ? ejb.suspendGlobalSubscription(ctx)
                    : ejb.deleteSubscription(ctx);
        } catch (Exception e) {
            throw new DicomServiceException(Status.ProcessingFailure, e);
        }
    }

    private static IOD loadIOD(String name) {
        try {
            IOD iod = new IOD();
            iod.parse(UPSServiceImpl.class.getResource(name).toString());
            iod.trimToSize();
            return iod;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void validateSubscriberAET(UPSContext ctx) throws DicomServiceException, ConfigurationException {
        for (String aet : ctx.getArchiveAEExtension().upsEventSCUs()) {
            if (aet.equals(ctx.getSubscriberAET())) {
                try {
                    aeCache.findApplicationEntity(aet);
                } catch (ConfigurationNotFoundException e) {
                    throw new DicomServiceException(Status.UPSUnknownReceivingAET, e);
                }
            }
        }
    }

    private List<GlobalSubscription> globalSubscriptions(Attributes attrs) {
        return ejb.findGlobalSubscriptions().stream()
                .filter(sub -> matches(attrs, sub.getMatchKeys()))
                .collect(Collectors.toList());
    }

    private static boolean matches(Attributes attrs, Attributes keys) {
        return keys == null || attrs.matches(keys, false, false);
    }

    private List<Attributes> searchNotSubscribedUPS(UPSContext ctx) throws DicomServiceException {
        List<Attributes> list = new ArrayList<>();
        ApplicationEntity ae = ctx.getApplicationEntity();
        ArchiveDeviceExtension arcdev = ctx.getArchiveDeviceExtension();
        QueryParam queryParam = new QueryParam(ae);
        queryParam.setNotSubscribedByAET(ctx.getSubscriberAET());
        QueryContext queryContext = queryService.newQueryContext(ae, queryParam);
        Attributes matchKeys = ctx.getAttributes();
        if (matchKeys != null) {
            IDWithIssuer idWithIssuer = IDWithIssuer.pidOf(matchKeys);
            if (idWithIssuer != null && !idWithIssuer.getID().equals("*"))
                queryContext.setPatientIDs(idWithIssuer);
            queryContext.setQueryKeys(matchKeys);
        } else {
            queryContext.setQueryKeys(new Attributes(0));
        }
        try (Query query = queryService.createUPSWithoutQueryEvent(queryContext)) {
            query.executeQuery(arcdev.getQueryFetchSize());
            while (query.hasMoreMatches()) {
                list.add(query.nextMatch());
            }
        }
        return list;
    }
}
