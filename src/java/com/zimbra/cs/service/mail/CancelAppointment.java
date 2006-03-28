/*
 * ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1
 * 
 * The contents of this file are subject to the Mozilla Public License
 * Version 1.1 ("License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.zimbra.com/license
 * 
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See
 * the License for the specific language governing rights and limitations
 * under the License.
 * 
 * The Original Code is: Zimbra Collaboration Suite Server.
 * 
 * The Initial Developer of the Original Code is Zimbra, Inc.
 * Portions created by Zimbra are Copyright (C) 2005 Zimbra, Inc.
 * All Rights Reserved.
 * 
 * Contributor(s): 
 * 
 * ***** END LICENSE BLOCK *****
 */

package com.zimbra.cs.service.mail;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.mail.Address;
import javax.mail.MessagingException;
import javax.mail.internet.MimeBodyPart;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.zimbra.cs.account.Account;
import com.zimbra.cs.mailbox.Appointment;
import com.zimbra.cs.mailbox.MailSender;
import com.zimbra.cs.mailbox.MailServiceException;
import com.zimbra.cs.mailbox.Mailbox;
import com.zimbra.cs.mailbox.Mailbox.OperationContext;
import com.zimbra.cs.mailbox.calendar.CalendarL10n;
import com.zimbra.cs.mailbox.calendar.CalendarMailSender;
import com.zimbra.cs.mailbox.calendar.Invite;
import com.zimbra.cs.mailbox.calendar.RecurId;
import com.zimbra.cs.mailbox.calendar.CalendarL10n.MsgKey;
import com.zimbra.cs.mailbox.calendar.ZCalendar.ICalTok;
import com.zimbra.cs.mailbox.calendar.ZCalendar.ZVCalendar;
import com.zimbra.cs.service.ServiceException;
import com.zimbra.cs.service.util.ItemId;
import com.zimbra.soap.Element;
import com.zimbra.soap.ZimbraContext;

public class CancelAppointment extends CalendarRequest {

    private static Log sLog = LogFactory.getLog(CancelAppointment.class);
    private static final String[] TARGET_ITEM_PATH = new String[] { MailService.A_ID };
    protected String[] getProxiedIdPath(Element request)     { return TARGET_ITEM_PATH; }
    protected boolean checkMountpointProxy(Element request)  { return false; }

    public Element handle(Element request, Map context) throws ServiceException {
        ZimbraContext lc = getZimbraContext(context);
        Account acct = getRequestedAccount(lc);
        Mailbox mbox = getRequestedMailbox(lc);
        OperationContext octxt = lc.getOperationContext();
        
        ItemId iid = new ItemId(request.getAttribute(MailService.A_ID), lc);
        int compNum = (int) request.getAttributeLong(MailService.E_INVITE_COMPONENT);
        
        sLog.info("<CancelAppointment id=" + iid + " comp=" + compNum + ">");
        
        synchronized (mbox) {
            Appointment appt = mbox.getAppointmentById(octxt, iid.getId()); 
            Invite inv = appt.getInvite(iid.getSubpartId(), compNum);
            
            if (appt == null) {
                throw MailServiceException.NO_SUCH_APPT(inv.getUid(), " for CancelAppointmentRequest(" + iid + "," + compNum + ")");
            }
            
            Element recurElt = request.getOptionalElement(MailService.E_INSTANCE);
            if (recurElt != null) {
                RecurId recurId = CalendarUtils.parseRecurId(recurElt, inv.getTimeZoneMap(), inv);
                cancelInstance(lc, request, acct, mbox, appt, inv, recurId);
            } else {
                
                // if recur is not set, then we're cancelling the entire appointment...
                
                // first, pull a list of all the invites and THEN start cancelling them: since cancelling them
                // will remove them from the appointment's list, we can get really confused if we just directly
                // iterate through the list...
                
                Invite invites[] = new Invite[appt.numInvites()];
                for (int i = appt.numInvites()-1; i >= 0; i--) {
                    invites[i] = appt.getInvite(i);
                }
                
                for (int i = invites.length-1; i >= 0; i--) {
                    if (invites[i] != null && 
                        (invites[i].getMethod().equals(ICalTok.REQUEST.toString()) ||
                            invites[i].getMethod().equals(ICalTok.PUBLISH.toString()))
                    ) {
                        cancelInvite(lc, request, acct, mbox, appt, invites[i]);
                    }
                }
            }
        } // synchronized on mailbox
        
        Element response = lc.createElement(MailService.CANCEL_APPOINTMENT_RESPONSE);
        return response;
    }        
    
    void cancelInstance(ZimbraContext lc, Element request, Account acct, Mailbox mbox, Appointment appt, Invite defaultInv, RecurId recurId) 
    throws ServiceException {
        Locale locale = acct.getLocale();
        String text = CalendarL10n.getMessage(MsgKey.cancelAppointmentInstance, locale);

        if (sLog.isDebugEnabled()) {
            sLog.debug("Sending cancellation message for \"" + defaultInv.getName() + "\" for instance " + recurId + " of invite " + defaultInv);
        }

        Invite cancelInvite = CalendarUtils.buildCancelInstanceCalendar(acct, defaultInv, text, recurId);
        CalSendData dat = new CalSendData();
        dat.mOrigId = defaultInv.getMailItemId();
        dat.mReplyType = MailSender.MSGTYPE_REPLY;
        dat.mSaveToSent = acct.saveToSent();
        dat.mInvite = cancelInvite;

        ZVCalendar iCal = dat.mInvite.newToICalendar();

        // did they specify a custom <m> message?  If so, then we don't have to build one...
        Element msgElem = request.getOptionalElement(MailService.E_MSG);

        if (msgElem != null) {
            MimeBodyPart[] mbps = new MimeBodyPart[1];
            mbps[0] = CalendarMailSender.makeICalIntoMimePart(defaultInv.getUid(), iCal);

            // the <inv> element is *NOT* allowed -- we always build it manually
            // based on the params to the <CancelAppointment> and stick it in the 
            // mbps (additionalParts) parameter...
            dat.mMm = ParseMimeMessage.parseMimeMsgSoap(lc, mbox, msgElem, mbps, 
                    ParseMimeMessage.NO_INV_ALLOWED_PARSER, dat);
            
        } else {
            List<Address> rcpts =
                CalendarMailSender.toListFromAttendees(defaultInv.getAttendees());
            dat.mMm = CalendarMailSender.createCancelMessage(
                    acct, rcpts, defaultInv, cancelInvite, text, iCal);
        }
        
        if (!defaultInv.thisAcctIsOrganizer(acct)) {
            try {
                Address[] rcpts = dat.mMm.getAllRecipients();
                if (rcpts != null && rcpts.length > 0) {
                    throw MailServiceException.MUST_BE_ORGANIZER("CancelAppointment");
                }
            } catch (MessagingException e) {
                throw ServiceException.FAILURE("Checking recipients of outgoing msg ", e);
            }
        }
        
        sendCalendarCancelMessage(lc, appt.getFolderId(),
                                  acct, mbox, dat, true);
    }

    protected void cancelInvite(ZimbraContext lc, Element request, Account acct, Mailbox mbox, Appointment appt, Invite inv)
    throws ServiceException {
        Locale locale = acct.getLocale();
        String text = CalendarL10n.getMessage(MsgKey.cancelAppointment, locale);

        if (sLog.isDebugEnabled())
            sLog.debug("Sending cancellation message for \"" + inv.getName() + "\" for " + inv.toString());
        
        CalSendData dat = new CalSendData();
        dat.mOrigId = inv.getMailItemId();
        dat.mReplyType = MailSender.MSGTYPE_REPLY;
        dat.mSaveToSent = acct.saveToSent();
        dat.mInvite = CalendarUtils.buildCancelInviteCalendar(acct, inv, text);
        
        ZVCalendar iCal = dat.mInvite.newToICalendar();
        
        
        // did they specify a custom <m> message?  If so, then we don't have to build one...
        Element msgElem = request.getOptionalElement(MailService.E_MSG);
        
        if (msgElem != null) {
            MimeBodyPart[] mbps = new MimeBodyPart[1];
            mbps[0] = CalendarMailSender.makeICalIntoMimePart(inv.getUid(), iCal);
            
            // the <inv> element is *NOT* allowed -- we always build it manually
            // based on the params to the <CancelAppointment> and stick it in the 
            // mbps (additionalParts) parameter...
            dat.mMm = ParseMimeMessage.parseMimeMsgSoap(lc, mbox, msgElem, mbps, 
                    ParseMimeMessage.NO_INV_ALLOWED_PARSER, dat);
            
        } else {
            List<Address> rcpts =
                CalendarMailSender.toListFromAttendees(inv.getAttendees());
            dat.mMm = CalendarMailSender.createCancelMessage(
                    acct, rcpts, inv, null, text, iCal);
        }
        
        if (!inv.thisAcctIsOrganizer(acct)) {
            try {
                Address[] rcpts = dat.mMm.getAllRecipients();
                if (rcpts != null && rcpts.length > 0) {
                    throw MailServiceException.MUST_BE_ORGANIZER("CancelAppointment");
                }
            } catch (MessagingException e) {
                throw ServiceException.FAILURE("Checking recipients of outgoing msg ", e);
            }
        }
        
        sendCalendarCancelMessage(lc, appt.getFolderId(),
                                  acct, mbox, dat, true);
    }
}
