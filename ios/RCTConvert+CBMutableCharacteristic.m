#import "RCTConvert+CBMutableCharacteristic.h"
#import "RCTConvert+CBAttributePermissions.h"
#import "RCTConvert+CBCharacteristicProperties.h"
#import "RCTConvert+CBUUID.h"

@implementation RCTConvert (CBMutableCharacteristic)

+ (CBMutableCharacteristic *)CBMutableCharacteristic:(id)json {
  return [[CBMutableCharacteristic alloc]
      initWithType:[RCTConvert CBUUID:json[@"uuid"]]
        properties:[RCTConvert CBCharacteristicProperties:json[@"properties"]]
             value:[RCTConvert NSData:json[@"data"]]
       permissions:[RCTConvert CBAttributePermissions:json[@"permissions"]]];
}

@end
