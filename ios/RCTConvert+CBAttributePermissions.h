#import <React/RCTConvert.h>
#import <CoreBluetooth/CoreBluetooth.h>

@interface RCTConvert (CBAttributePermissions)

+ (CBAttributePermissions)CBAttributePermissions:(id)json;

@end
