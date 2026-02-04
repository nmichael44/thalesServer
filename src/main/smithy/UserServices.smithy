$version: "2.0"

namespace app.entrypoints.smithy

use alloy#simpleRestJson
use smithy4s.meta#vector

@httpBearerAuth
@auth([httpBearerAuth])
@simpleRestJson
service UserServices {
    version: "1.0.0",
    operations: [CreateUser
                 FetchUsersByLoginNames
                 FetchUsersByUserIds
                 FetchAllUsersAssociatedWithRoles
                 ResetMyPassword
                 CheckResetUserPasswordToken
                 FetchAllLiveSessions
                 SetMustResetUserPassword]
}

@input
structure CreateUserInput {
    @required
    user: User
}

@output
structure CreateUserOutput {
    @required
    userId: UserId
}

@http(method: "POST", uri: "/api/createUser", code: 200)
operation CreateUser {
    input: CreateUserInput
    output: CreateUserOutput
    errors: [UserIsUnAuthenticated, UserForbiddenFromCallingEntryPoint, InvalidInputParameters, DuplicateParamEncountered]
}

@http(method: "POST", uri: "/api/fetchUsersByLoginNames", code: 200)
operation FetchUsersByLoginNames {
    input: FetchUsersByLoginNamesInput
    output: FetchUsersByLoginNamesOutput
    errors: [UserIsUnAuthenticated, UserForbiddenFromCallingEntryPoint]
}

structure FetchUsersByLoginNamesInput {
    @required
    loginNames: LoginNameList
}

structure FetchUsersByLoginNamesOutput {
    @required
    users: UserMapByLoginName
}

map UserMapByLoginName {
    key: String
    value: UserInDb
}

@http(method: "POST", uri: "/api/fetchUsersByUserIds", code: 200)
operation FetchUsersByUserIds {
    input: FetchUsersByUserIdsInput
    output: FetchUsersByUserIdsOutput
    errors: [UserIsUnAuthenticated, UserForbiddenFromCallingEntryPoint]
}

structure FetchUsersByUserIdsInput {
    @required
    userIds: UserIdList
}

structure FetchUsersByUserIdsOutput {
    @required
    users: UserMapById
}

map UserMapById {
    key: String
    value: UserInDb
}

@http(method: "POST", uri: "/api/fetchAllUsersAssociatedWithRoles", code: 200)
operation FetchAllUsersAssociatedWithRoles {
    input: FetchAllUsersAssociatedWithRolesInput
    output: FetchAllUsersAssociatedWithRolesOutput
    errors: [UserIsUnAuthenticated, UserForbiddenFromCallingEntryPoint]
}

structure FetchAllUsersAssociatedWithRolesInput {
    @required
    roleIds: RoleIdList
}

structure FetchAllUsersAssociatedWithRolesOutput {
    @required
    roleIdToUsers: RoleIdToUsersMap
}

map RoleIdToUsersMap {
    key: String
    value: UserList
}

@vector
list UserList {
    member: UserInDb
}

@http(method: "POST", uri: "/api/resetMyPassword", code: 200)
operation ResetMyPassword {
    input: ResetMyPasswordInput
    errors: [UserIsUnAuthenticated, UserForbiddenFromCallingEntryPoint, PasswordIsInvalid]
}

structure ResetMyPasswordInput {
    @required
    newPassword: UserPassword
}

@http(method: "POST", uri: "/api/checkResetUserPasswordToken", code: 200)
operation CheckResetUserPasswordToken {
    input: CheckResetUserPasswordTokenInput
    errors: [UserIsUnAuthenticated, UserForbiddenFromCallingEntryPoint, InvalidOrMissingResetPasswordToken]
}

structure CheckResetUserPasswordTokenInput {
    @required
    token: ResetPasswordToken
}

@http(method: "POST", uri: "/api/fetchAllLiveSessions", code: 200)
operation FetchAllLiveSessions {
    output: FetchAllLiveSessionsOutput
    errors: [UserIsUnAuthenticated, UserForbiddenFromCallingEntryPoint]
}

structure FetchAllLiveSessionsOutput {
    @required
    userSessions: UserSessionList
}

@http(method: "POST", uri: "/api/setMustResetUserPassword", code: 200)
operation SetMustResetUserPassword {
    input: SetMustResetUserPasswordInput
    errors: [UserIsUnAuthenticated, UserForbiddenFromCallingEntryPoint, UserNotFound]
}

structure SetMustResetUserPasswordInput {
    @required
    userId: UserId

    @required
    mustResetPassword: Boolean
}
