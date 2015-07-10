#!/bin/bash
#
# CODENVY CONFIDENTIAL
# ________________
#
# [2012] - [2015] Codenvy, S.A.
# All Rights Reserved.
# NOTICE: All information contained herein is, and remains
# the property of Codenvy S.A. and its suppliers,
# if any. The intellectual and technical concepts contained
# herein are proprietary to Codenvy S.A.
# and its suppliers and may be covered by U.S. and Foreign Patents,
# patents in process, and are protected by trade secret or copyright law.
# Dissemination of this information or reproduction of this material
# is strictly forbidden unless prior written permission is obtained
# from Codenvy S.A..
#

[ -f "./lib.sh" ] && . ./lib.sh
[ -f "../lib.sh" ] && . ../lib.sh

printAndLog "TEST CASE: Login as where user which doesn't have own account"

vagrantUp ${SINGLE_NODE_VAGRANT_FILE}

installImCliClient
validateInstalledImCliClientVersion

executeIMCommand "--valid-exit-code=1" "login" "${CODENVY_SAAS_USER_WITHOUT_OWN_ACCOUNT_NAME}" "${CODENVY_SAAS_USER_WITHOUT_OWN_ACCOUNT_PASSWORD}"

if [[ ! ${OUTPUT} =~ .*You.are.logged.as.a.user.which.does.not.have.an.account/owner.role.in.any.account\..This.likely.means.that.you.used.the.wrong.credentials.to.access.Codenvy\..* ]]; then
    validateExitCode 1
fi

printAndLog "RESULT: PASSED"

vagrantDestroy
