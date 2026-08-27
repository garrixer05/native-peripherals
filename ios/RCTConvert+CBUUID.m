#import "RCTConvert+CBUUID.h"

@implementation RCTConvert (CBUUID)

+ (CBUUID *)CBUUID:(id)json {
  return [CBUUID UUIDWithString:json];
}

@end
