#import <React/RCTConvert.h>
#import <CoreBluetooth/CoreBluetooth.h>

@interface RCTConvert (CBUUID)

+ (CBUUID *)CBUUID:(id)json;

@end
