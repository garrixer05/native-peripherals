#import "RCTConvert+CBAttributePermissions.h"

@implementation RCTConvert (CBAttributePermissions)

RCT_MULTI_ENUM_CONVERTER(CBAttributePermissions, (@{
                           @"readable" : @(CBAttributePermissionsReadable),
                           @"writeable" : @(CBAttributePermissionsWriteable),
                           @"readEncryptionRequired" :
                               @(CBAttributePermissionsReadEncryptionRequired),
                           @"writeEncryptionRequired" :
                               @(CBAttributePermissionsWriteEncryptionRequired),
                         }),
                         0, integerValue)

@end
